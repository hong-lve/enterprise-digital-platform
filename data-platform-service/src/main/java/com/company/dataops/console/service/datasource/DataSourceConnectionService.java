package com.company.dataops.console.service.datasource;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.service.query.ColumnView;
import com.company.dataops.console.service.query.QueryResult;
import com.company.dataops.console.service.query.TableView;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generic JDBC access driven by a registered DataSourceEntity instead of a
 * fixed @Value-injected connection - replaces the old ClickHouseQueryService/
 * DorisQueryService, which each hard-wired exactly one connection from
 * application.yml. Every method takes the DataSourceEntity (and optionally
 * which database on it) at call time, so any number of MySQL/ClickHouse/
 * Doris/Oracle instances can be registered and queried without a code change.
 *
 * "database" means two different things depending on engine, which
 * buildJdbcUrl() has to reconcile: for MySQL/ClickHouse/Doris it's a
 * genuinely separate connectable catalog, so reconnecting with a different
 * value in the URL when the user switches the "选择数据库" dropdown is
 * correct. Oracle has no such thing - the URL's service-name/PDB segment is
 * fixed per instance; what the dropdown lists for Oracle (all_users) are
 * schemas/owners, a namespace *within* that one connection, not a separate
 * connection target. Passing a schema name where Oracle expects a service
 * name throws ORA-12514 ("service ... not registered with the listener") -
 * confirmed live. So for ORACLE, buildJdbcUrl() always connects to a fixed
 * service name regardless of what's passed; tables()/columns() still filter
 * by the selected schema via WHERE owner=..., and query() switches
 * CURRENT_SCHEMA after connecting so unqualified table references in the
 * user's SQL resolve against the schema they picked, matching the "selected
 * database becomes the default" UX the other three engines get for free
 * from their URL-based connection switch.
 *
 * That fixed service name is pdbName when set, not databaseName. In a
 * multitenant instance (this project's docker/bigdata Oracle Free image
 * included) databaseName is the CDB root's service (e.g. "FREE") and the
 * PDB holding the actual application schemas is a separate service (e.g.
 * "FREEPDB1") - confirmed live that connecting to the root service makes
 * every PDB-local schema (including this project's own "REALTIME" CDC
 * source schema) invisible to listDatabases()/tables(), even though
 * DebeziumConnectorConfigBuilder already targets that same PDB correctly
 * for the CDC connector itself. Falls back to databaseName when pdbName
 * isn't set, for a non-multitenant Oracle instance with no PDB at all.
 */
@Service
public class DataSourceConnectionService {
    private final DataSourceMapper dataSourceMapper;

    public DataSourceConnectionService(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    public String buildJdbcUrl(DataSourceEntity dataSource, String database) {
        String type = dataSource.getType().toUpperCase(Locale.ROOT);
        String db = "ORACLE".equals(type)
            ? (dataSource.getPdbName() == null || dataSource.getPdbName().isBlank() ? dataSource.getDatabaseName() : dataSource.getPdbName())
            : (database == null || database.isBlank() ? dataSource.getDatabaseName() : database);
        return switch (type) {
            // Doris speaks the MySQL wire protocol on its FE query port.
            case "MYSQL", "DORIS" -> "jdbc:mysql://" + dataSource.getHost() + ":" + dataSource.getPort() + "/" + (db == null ? "" : db);
            case "CLICKHOUSE" -> "jdbc:clickhouse://" + dataSource.getHost() + ":" + dataSource.getPort() + "/" + (db == null ? "" : db);
            case "ORACLE" -> "jdbc:oracle:thin:@//" + dataSource.getHost() + ":" + dataSource.getPort() + "/" + (db == null ? "" : db);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的数据源类型：" + dataSource.getType());
        };
    }

    private Connection openConnection(DataSourceEntity dataSource, String database) {
        try {
            return DriverManager.getConnection(buildJdbcUrl(dataSource, database), dataSource.getUsername(), dataSource.getPassword());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接数据源失败：" + exception.getMessage(), exception);
        }
    }

    public DataSourceEntity testConnection(DataSourceEntity dataSource) {
        String status;
        String error = null;
        // Some JDBC drivers (e.g. ClickHouse's HTTP-based driver) hand back a
        // Connection object lazily without any real network I/O - opening
        // and immediately closing it never contacts the server, so it always
        // "succeeds" even against an unreachable host. Confirmed live: a
        // data source pointed at a nonexistent ClickHouse host still reported
        // OK until this query was added. Running a trivial statement forces
        // an actual round trip for every engine.
        String probeSql = "ORACLE".equalsIgnoreCase(dataSource.getType()) ? "SELECT 1 FROM DUAL" : "SELECT 1";
        try (Connection connection = openConnection(dataSource, dataSource.getDatabaseName());
             Statement statement = connection.createStatement()) {
            statement.execute(probeSql);
            status = "OK";
        } catch (Exception exception) {
            status = "FAILED";
            error = exception.getMessage();
        }
        dataSourceMapper.update(null, new LambdaUpdateWrapper<DataSourceEntity>()
            .eq(DataSourceEntity::getId, dataSource.getId())
            .set(DataSourceEntity::getStatus, status)
            .set(DataSourceEntity::getLastTestError, error));
        dataSource.setStatus(status);
        dataSource.setLastTestError(error);
        return dataSource;
    }

    public List<String> listDatabases(DataSourceEntity dataSource) {
        String type = dataSource.getType().toUpperCase(Locale.ROOT);
        String sql = switch (type) {
            case "MYSQL" -> "SELECT SCHEMA_NAME FROM information_schema.schemata "
                + "WHERE SCHEMA_NAME NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') ORDER BY SCHEMA_NAME";
            // Doris speaks the same information_schema.schemata as MySQL
            // (it's wire-protocol compatible) but ships one more internal
            // metadata database of its own on top - confirmed live it has
            // '__internal_schema' in addition to the four MySQL already
            // excludes above, which a shared MYSQL/DORIS case would miss.
            case "DORIS" -> "SELECT SCHEMA_NAME FROM information_schema.schemata "
                + "WHERE SCHEMA_NAME NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys', '__internal_schema') ORDER BY SCHEMA_NAME";
            // Confirmed live this instance has all of: 'default' (ClickHouse's
            // own always-present database), 'system' (its metadata/query-log
            // database), and BOTH 'information_schema' and 'INFORMATION_SCHEMA'
            // (it keeps separate lower/upper-case entries for SQL-standard
            // compatibility, not just a case-insensitive alias of one).
            case "CLICKHOUSE" -> "SELECT name FROM system.databases "
                + "WHERE name NOT IN ('default', 'system', 'information_schema', 'INFORMATION_SCHEMA') ORDER BY name";
            // ORACLE_MAINTAINED (12.2+) is Oracle's own flag for accounts it
            // ships out of the box (SYS, SYSTEM, XDB, ...) vs. ones a real
            // user created - confirmed live inside this project's own PDB
            // (FREEPDB1, once buildJdbcUrl() targets it instead of the CDB
            // root): 'N' picked out exactly this project's own schemas
            // (REALTIME, C##DBZUSER, PDBADMIN) among 30+ builtin ones.
            // Filtering on this instead of a hardcoded name list mirrors the
            // MYSQL/DORIS case above without having to hand-maintain a list
            // of every Oracle-shipped schema across versions. Note this
            // would wrongly hide SYSTEM too if application tables were ever
            // created directly under it instead of a dedicated schema -
            // this project's own Oracle sink tables were migrated off of
            // SYSTEM into REALTIME specifically to avoid that trap.
            //
            // PDBADMIN still slips through ORACLE_MAINTAINED='N' - it's the
            // PDB's own administrator account, auto-created (always under
            // this exact name by every Oracle install/tutorial/Docker image
            // that provisions a PDB) when the pluggable database itself was
            // created, not when a user ran CREATE USER. Oracle's flag only
            // tracks "shipped as part of Oracle's software," which this
            // technically isn't, so it's excluded by name here the same way
            // MYSQL/DORIS above exclude their own conventionally-named
            // system schemas - confirmed live it never holds any tables.
            case "ORACLE" -> "SELECT username FROM all_users WHERE oracle_maintained = 'N' AND username != 'PDBADMIN' ORDER BY username";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的数据源类型：" + dataSource.getType());
        };
        // Connecting without pinning to a specific database first - MySQL/
        // Doris/ClickHouse all accept an empty path segment in the JDBC URL
        // for "no default database", Oracle's service-name slot can't be
        // empty so this will fail for Oracle same as everywhere else (no
        // driver registered) rather than needing special-casing here.
        try (Connection connection = openConnection(dataSource, dataSource.getDatabaseName());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> databases = new ArrayList<>();
            while (resultSet.next()) {
                databases.add(resultSet.getString(1));
            }
            return databases;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取数据库列表失败：" + exception.getMessage(), exception);
        }
    }

    public List<TableView> tables(DataSourceEntity dataSource, String database) {
        String type = dataSource.getType().toUpperCase(Locale.ROOT);
        String db = database == null || database.isBlank() ? dataSource.getDatabaseName() : database;
        // Bound with ? placeholders instead of splicing db into the SQL text -
        // quoteLiteral()'s doubled-quote escaping used to be the only guard
        // here, but MySQL/Doris/ClickHouse all treat backslash as a string
        // escape character by default, so a trailing backslash in db let a
        // later quote in the same literal close early and turn the rest of
        // the (attacker-controlled) value into live SQL. A bind parameter
        // is never re-parsed as SQL text, so that class of bypass doesn't
        // apply here regardless of what characters db contains.
        try (Connection connection = openConnection(dataSource, db)) {
            List<TableView> tables = new ArrayList<>();
            if ("CLICKHOUSE".equals(type)) {
                // clickhouse-jdbc 0.9.0's PreparedStatement is simply broken -
                // confirmed live with a standalone reproduction: binding even
                // a single ? throws ArrayIndexOutOfBoundsException from
                // PreparedStatementImpl.setString, and the driver's own
                // parse-error text in that same failure shows it mangling the
                // query text ("SELECTname" - the space is gone), so this
                // isn't specific to this query's shape, it's broken for
                // parameterized queries on this driver version in general.
                // Can't fall back to splicing db in unvalidated (that's the
                // exact SQL-injection shape the bind-param switch above this
                // method was fixing) - validating it's a bare identifier
                // first, then inlining the now-guaranteed-safe value as a
                // literal, gets the same safety a bind parameter would have
                // given without needing the driver to support one.
                if (!db.matches("[A-Za-z0-9_]+")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的数据库名称");
                }
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT name, engine, comment FROM system.tables WHERE database = '" + db + "' ORDER BY name")) {
                    while (resultSet.next()) {
                        tables.add(new TableView(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)));
                    }
                }
                return tables;
            }
            String sql = switch (type) {
                case "MYSQL", "DORIS" -> "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT FROM information_schema.tables WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME";
                case "ORACLE" -> "SELECT table_name, 'TABLE', NULL FROM all_tables WHERE owner = ? ORDER BY table_name";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的数据源类型：" + dataSource.getType());
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, db);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        tables.add(new TableView(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)));
                    }
                }
            }
            return tables;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取表列表失败：" + exception.getMessage(), exception);
        }
    }

    public List<ColumnView> columns(DataSourceEntity dataSource, String database, String table) {
        if (!table.matches("[A-Za-z0-9_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的表名");
        }
        String type = dataSource.getType().toUpperCase(Locale.ROOT);
        String db = database == null || database.isBlank() ? dataSource.getDatabaseName() : database;
        try (Connection connection = openConnection(dataSource, db)) {
            List<ColumnView> columns = new ArrayList<>();
            if ("CLICKHOUSE".equals(type)) {
                // Same clickhouse-jdbc 0.9.0 PreparedStatement bug as tables()
                // above - db and table (already validated at the top of this
                // method) get inlined as literals instead of bound.
                if (!db.matches("[A-Za-z0-9_]+")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的数据库名称");
                }
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("SELECT name, type, comment FROM system.columns WHERE database = '" + db + "' AND table = '" + table + "' ORDER BY position")) {
                    while (resultSet.next()) {
                        columns.add(new ColumnView(resultSet.getString(1), resultSet.getString(2), null, resultSet.getString(3)));
                    }
                }
                return columns;
            }
            String sql = switch (type) {
                case "MYSQL", "DORIS" -> "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_COMMENT FROM information_schema.columns WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
                case "ORACLE" -> "SELECT column_name, data_type, NULL FROM all_tab_columns WHERE owner = ? AND table_name = ? ORDER BY column_id";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的数据源类型：" + dataSource.getType());
            };
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, db);
                statement.setString(2, table);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        columns.add(new ColumnView(resultSet.getString(1), resultSet.getString(2), null, resultSet.getString(3)));
                    }
                }
            }
            return columns;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取字段列表失败：" + exception.getMessage(), exception);
        }
    }

    public QueryResult query(DataSourceEntity dataSource, String database, String sql, int limit) {
        assertSelect(sql);
        String limitedSql = withLimit(sql, limit, dataSource.getType());
        try (Connection connection = openConnection(dataSource, database); Statement statement = connection.createStatement()) {
            if ("ORACLE".equalsIgnoreCase(dataSource.getType()) && database != null && !database.isBlank()) {
                // The connection above always targets this data source's own
                // service name (see buildJdbcUrl()) regardless of the picked
                // schema, so an unqualified table name in the user's SQL
                // would otherwise resolve against the connected user's own
                // schema, not the one they selected in "选择数据库".
                if (!database.matches("[A-Za-z0-9_$#]+")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的 schema 名称");
                }
                statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + database);
            }
            try (ResultSet resultSet = statement.executeQuery(limitedSql)) {
                List<String> columns = new ArrayList<>();
                int columnCount = resultSet.getMetaData().getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(resultSet.getMetaData().getColumnLabel(i));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String column : columns) {
                        row.put(column, resultSet.getObject(column));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows);
            }
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "执行查询失败：" + exception.getMessage(), exception);
        }
    }

    // "SELECT-only" used to mean "starts with SELECT/WITH" - true for the
    // statement's outer shape, but ClickHouse table functions (url/mysql/
    // postgresql/odbc/jdbc/remote/hdfs/s3/file/executable) and MySQL/Doris's
    // SELECT ... INTO OUTFILE/DUMPFILE and LOAD_FILE() are all still valid
    // inside a SELECT and reach well outside this database: the former lets
    // a SELECT fetch an arbitrary URL or internal host (SSRF), the latter
    // reads/writes arbitrary files on the DB server. None of those are
    // needed for the feature this powers (previewing rows from a registered
    // data source), so they're blocked outright rather than sandboxed.
    private static final Pattern FORBIDDEN_TOKEN = Pattern.compile(
        "\\binto\\s+(outfile|dumpfile)\\b"
            + "|\\bload_file\\s*\\("
            + "|\\b(url|mysql|postgresql|odbc|jdbc|remote|remotesecure|hdfs|s3|file|executable)\\s*\\(",
        Pattern.CASE_INSENSITIVE
    );

    private void assertSelect(String sql) {
        String normalized = sql == null ? "" : sql.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("select") && !normalized.startsWith("with")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实时数据查询只允许执行 SELECT 查询");
        }
        if (normalized.contains(";")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不允许一次执行多条语句");
        }
        if (FORBIDDEN_TOKEN.matcher(sql).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询中包含不允许的表函数或文件读写操作");
        }
    }

    private String withLimit(String sql, int limit, String type) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        int capped = Math.max(1, Math.min(limit, 500));
        // Oracle has never supported the MySQL/ClickHouse-style LIMIT
        // clause the other three engines share - confirmed live (ORA-03049,
        // "LIMIT is not valid at this position"). FETCH FIRST ... ROWS ONLY
        // is its SQL:2008 equivalent (Oracle 12c+, which this environment's
        // Oracle Free image comfortably postdates).
        if ("ORACLE".equalsIgnoreCase(type)) {
            if (normalized.matches("(?s).*\\bfetch\\s+first\\s+\\d+.*") || normalized.contains("rownum")) {
                return sql;
            }
            return sql + " FETCH FIRST " + capped + " ROWS ONLY";
        }
        if (normalized.matches("(?s).*\\blimit\\s+\\d+.*")) {
            return sql;
        }
        return sql + " LIMIT " + capped;
    }
}
