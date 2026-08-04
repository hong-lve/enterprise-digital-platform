package com.company.dataops.console.service.kafka;

import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.entity.DataSourceEntity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generates ready-to-paste Flink SQL CREATE TABLE statements for the "SQL
 * 流作业" editor by introspecting the actual MySQL/Oracle table a CDC source
 * captures - using the same connection details CdcSourceEntity already
 * stores for Debezium (see DebeziumConnectorConfigBuilder), via plain JDBC
 * (mysql-connector-j/ojdbc11 are already pom.xml dependencies). Follows
 * ClickHouseQueryService's "plain JDBC + try-with-resources" style rather
 * than introducing a second persistence layer for a one-shot read.
 */
@Service
public class CdcTableSchemaService {
    // Embedded into generated Flink SQL DDL (buildDdl() below) that gets
    // submitted to and executed by Flink's own containers, not this JVM - so
    // this needs Kafka's docker-network address, same reasoning as
    // DebeziumConnectorConfigBuilder's schema-history bootstrap servers.
    private final String kafkaBootstrapServers;
    // Same docker-network-address reasoning as kafkaBootstrapServers above -
    // Flink's debezium-avro-confluent format resolves each record's Avro
    // schema from this registry at runtime (see CdcMirrorSupport's own copy
    // of this same client-side resolution for the JAR-job path).
    private final String schemaRegistryUrl;
    // kafka:9092 is SSL-only (see docker-compose.remote.yml's x-kafka-common-
    // env) - without these, a generated Kafka source table's AdminClient
    // tries a plaintext handshake against an SSL-only port and every job
    // fails at startup with "Failed to get metadata for topics" /
    // "TimeoutException: The AdminClient thread has exited" (confirmed live:
    // every "SQL 流作业" job crash-looped with exactly this error until this
    // was added). CdcMirrorSupport (the JAR-job path) already sets the
    // equivalent KafkaSource properties directly in Java; this is the same
    // fix for the declarative 'connector'='kafka' WITH-clause path. The
    // truststore path matches every flink-jobmanager/taskmanager/sql-gateway
    // container's identical bind mount (./kafka-tls/certs/truststore.p12 ->
    // /opt/flink/conf/kafka-truststore.p12).
    private final String kafkaTlsPassword;

    public CdcTableSchemaService(
            @Value("${platform.bigdata.kafka-bootstrap-servers-internal:kafka:9092}") String kafkaBootstrapServers,
            @Value("${platform.bigdata.schema-registry-url-internal}") String schemaRegistryUrl,
            @Value("${platform.bigdata.kafka-tls-password:}") String kafkaTlsPassword) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.schemaRegistryUrl = schemaRegistryUrl;
        this.kafkaTlsPassword = kafkaTlsPassword;
    }

    public List<String> listTables(CdcSourceEntity source) {
        return Arrays.stream(source.getTableIncludeList().split(","))
            .map(String::trim)
            .filter(table -> !table.isEmpty())
            .toList();
    }

    public TableDdl describeTable(CdcSourceEntity source, DataSourceEntity dataSource, String qualifiedTable) {
        if (!listTables(source).contains(qualifiedTable)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该表不在这个 CDC 源声明的表清单里：" + qualifiedTable);
        }
        String tableName = tableNamePart(qualifiedTable);
        TableSchema schema = readTableSchema(dataSource, schemaPart(qualifiedTable), tableName);
        if (schema.columns().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有读到任何字段，确认表是否存在：" + qualifiedTable);
        }
        return buildDdl(source, tableName, qualifiedTable, schema.columns(), schema.primaryKeys());
    }

    /**
     * Reads a table's column list + primary key from the MySQL/Oracle
     * instance a DataSourceEntity points at - shared by describeTable() (the
     * existing Kafka-source-table wizard) and SinkTableDdlBuilder (the
     * ClickHouse/Doris/MySQL/Oracle sink wizard), so both generate their
     * CREATE TABLE column lists from the exact same introspection instead of
     * two copies drifting apart. Always connects via dataSource.getHost()/
     * getPort() (this service's own JVM's perspective), not flinkHost/
     * flinkPort - introspection runs in-process, unlike Kafka Connect/Flink.
     */
    public TableSchema readTableSchema(DataSourceEntity dataSource, String schema, String tableName) {
        String type = dataSource.getType() == null ? "" : dataSource.getType().toUpperCase(Locale.ROOT);
        boolean oracle = "ORACLE".equals(type);
        String url = oracle
            ? "jdbc:oracle:thin:@//" + dataSource.getHost() + ":" + dataSource.getPort() + "/" + dataSource.getDatabaseName()
            : "jdbc:mysql://" + dataSource.getHost() + ":" + dataSource.getPort() + "/" + schema;
        try (Connection connection = DriverManager.getConnection(url, dataSource.getUsername(), dataSource.getPassword())) {
            if (oracle) {
                // The JDBC URL above connects to the CDB root service name
                // (databaseName, e.g. "FREE") - LogMiner needs that root
                // connection for CDC, but the schema/table being described
                // here actually lives inside a PDB. Without switching
                // container, all_tab_columns/all_constraints below see the
                // root's dictionary and return zero rows even though the
                // table genuinely exists (confirmed live: /tables lists it,
                // but describeTable failed with "没有读到任何字段" until this
                // switch was added).
                String pdbName = dataSource.getPdbName();
                if (pdbName == null || pdbName.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该 Oracle 数据源未配置 PDB 名称，无法定位表所在的可插拔数据库");
                }
                if (!pdbName.matches("[A-Za-z0-9_$#]+")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDB 名称格式不合法：" + pdbName);
                }
                try (var statement = connection.createStatement()) {
                    statement.execute("ALTER SESSION SET CONTAINER = " + pdbName);
                }
            }
            List<ColumnInfo> columns = oracle ? readColumnsOracle(connection, schema, tableName) : readColumnsMysql(connection, schema, tableName);
            List<String> primaryKeys = oracle ? readPrimaryKeysOracle(connection, schema, tableName) : readPrimaryKeysMysql(connection, schema, tableName);
            return new TableSchema(columns, primaryKeys);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "连接源数据库读取表结构失败：" + exception.getMessage(), exception);
        }
    }

    public String schemaPart(String qualifiedTable) {
        return requireQualified(qualifiedTable)[0];
    }

    public String tableNamePart(String qualifiedTable) {
        return requireQualified(qualifiedTable)[1];
    }

    private String[] requireQualified(String qualifiedTable) {
        String[] parts = qualifiedTable.split("\\.", 2);
        if (parts.length != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "表名格式应为 数据库名.表名：" + qualifiedTable);
        }
        return parts;
    }

    private List<ColumnInfo> readColumnsMysql(Connection connection, String schema, String tableName) throws Exception {
        String sql = "SELECT column_name, data_type, column_type, numeric_precision, numeric_scale "
            + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        List<ColumnInfo> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(new ColumnInfo(
                        resultSet.getString("column_name"),
                        resultSet.getString("data_type"),
                        resultSet.getString("column_type"),
                        resultSet.getObject("numeric_precision") == null ? null : resultSet.getInt("numeric_precision"),
                        resultSet.getObject("numeric_scale") == null ? null : resultSet.getInt("numeric_scale"),
                        false
                    ));
                }
            }
        }
        return columns;
    }

    private List<String> readPrimaryKeysMysql(Connection connection, String schema, String tableName) throws Exception {
        String sql = "SELECT column_name FROM information_schema.key_column_usage "
            + "WHERE table_schema = ? AND table_name = ? AND constraint_name = 'PRIMARY' ORDER BY ordinal_position";
        List<String> keys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    keys.add(resultSet.getString("column_name"));
                }
            }
        }
        return keys;
    }

    private List<ColumnInfo> readColumnsOracle(Connection connection, String schema, String tableName) throws Exception {
        String sql = "SELECT column_name, data_type, data_precision, data_scale FROM all_tab_columns "
            + "WHERE owner = ? AND table_name = ? ORDER BY column_id";
        List<ColumnInfo> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(new ColumnInfo(
                        resultSet.getString("column_name"),
                        resultSet.getString("data_type"),
                        resultSet.getString("data_type"),
                        resultSet.getObject("data_precision") == null ? null : resultSet.getInt("data_precision"),
                        resultSet.getObject("data_scale") == null ? null : resultSet.getInt("data_scale"),
                        true
                    ));
                }
            }
        }
        return columns;
    }

    private List<String> readPrimaryKeysOracle(Connection connection, String schema, String tableName) throws Exception {
        String sql = "SELECT acc.column_name FROM all_constraints ac "
            + "JOIN all_cons_columns acc ON ac.constraint_name = acc.constraint_name AND ac.owner = acc.owner "
            + "WHERE ac.owner = ? AND ac.table_name = ? AND ac.constraint_type = 'P' ORDER BY acc.position";
        List<String> keys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    keys.add(resultSet.getString("column_name"));
                }
            }
        }
        return keys;
    }

    private TableDdl buildDdl(CdcSourceEntity source, String tableName, String qualifiedTable, List<ColumnInfo> columns, List<String> primaryKeys) {
        String srcName = tableName + "_src";
        String topic = source.getTopicPrefix() + "." + qualifiedTable;

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(srcName).append(" (\n");
        ddl.append(columns.stream().map(column -> columnDdlLine(column, primaryKeys)).collect(Collectors.joining(",\n")));
        ddl.append("\n) WITH (\n");
        ddl.append("  'connector' = 'kafka',\n");
        ddl.append("  'topic' = '").append(topic).append("',\n");
        ddl.append("  'properties.bootstrap.servers' = '").append(kafkaBootstrapServers).append("',\n");
        appendKafkaSslProperties(ddl);
        ddl.append("  'scan.startup.mode' = 'earliest-offset',\n");
        ddl.append("  'format' = 'debezium-avro-confluent',\n");
        ddl.append("  'debezium-avro-confluent.schema-registry.url' = '").append(schemaRegistryUrl).append("'\n");
        ddl.append(");\n");

        boolean hasPrimaryKey = !primaryKeys.isEmpty();
        if (hasPrimaryKey) {
            String sinkName = tableName + "_sink";
            // The hint must not be a standalone line before CREATE TABLE:
            // FlinkSqlJobSubmissionService.assertJobShape() requires each
            // non-final split statement to *start* with "CREATE TABLE" after
            // trimming, and a leading full-line comment would defeat that -
            // so it rides along as a trailing comment on the opening line
            // instead.
            ddl.append("\nCREATE TABLE ").append(sinkName).append(" ( -- 起点骨架，按需修改 sink topic 名字和 SELECT 里的字段/过滤逻辑\n");
            ddl.append(columns.stream().map(column -> columnDdlLine(column, primaryKeys)).collect(Collectors.joining(",\n")));
            ddl.append(",\n  PRIMARY KEY (").append(String.join(", ", primaryKeys)).append(") NOT ENFORCED\n");
            ddl.append(") WITH (\n");
            ddl.append("  'connector' = 'upsert-kafka',\n");
            ddl.append("  'topic' = '").append(tableName).append("-sink',\n");
            ddl.append("  'properties.bootstrap.servers' = '").append(kafkaBootstrapServers).append("',\n");
            appendKafkaSslProperties(ddl);
            ddl.append("  'key.format' = 'json',\n");
            ddl.append("  'value.format' = 'json'\n");
            ddl.append(");\n\n");
            ddl.append("INSERT INTO ").append(sinkName).append(" SELECT ");
            ddl.append(columns.stream().map(column -> quoteIdentifier(column.name())).collect(Collectors.joining(", ")));
            ddl.append(" FROM ").append(srcName).append(";\n");
        }

        return new TableDdl(
            columns.stream().map(ColumnInfo::name).toList(),
            primaryKeys,
            hasPrimaryKey,
            ddl.toString()
        );
    }

    private void appendKafkaSslProperties(StringBuilder ddl) {
        if (kafkaTlsPassword == null || kafkaTlsPassword.isBlank()) {
            return;
        }
        ddl.append("  'properties.security.protocol' = 'SSL',\n");
        ddl.append("  'properties.ssl.truststore.location' = '/opt/flink/conf/kafka-truststore.p12',\n");
        ddl.append("  'properties.ssl.truststore.type' = 'PKCS12',\n");
        ddl.append("  'properties.ssl.truststore.password' = '").append(kafkaTlsPassword).append("',\n");
    }

    private String columnDdlLine(ColumnInfo column, List<String> primaryKeys) {
        String flinkType = mapKafkaSourceType(column);
        // Debezium's Oracle connector (unlike its MySQL connector) always
        // encodes the primary-key field's Avro type as a plain non-union
        // type in the "after"/"before" Value schema, even when every other
        // field is wrapped ["null", ...] - confirmed live by pulling the
        // actual registered schema from Schema Registry. Flink's
        // debezium-avro-confluent format's reader schema is derived from
        // this column's declared Flink type: leaving it nullable (Flink SQL's
        // default) makes Flink expect a union and fail with "Found ...Value,
        // expecting union" the moment it hits a real Oracle-sourced record -
        // confirmed live, every Oracle-sourced SQL 流作业 crash-looped with
        // exactly that error until this was added. Declaring the PK column
        // NOT NULL here isn't just a workaround - a primary key genuinely
        // can never be null - and it makes Flink derive a matching
        // non-union reader schema for that one field, which is what
        // resolves the mismatch.
        boolean isPrimaryKey = primaryKeys.contains(column.name());
        if (isPrimaryKey) {
            flinkType = flinkType + " NOT NULL";
        }
        // A trailing "-- comment" on the field's own line would swallow the
        // separating comma that Collectors.joining(",\n") (or the explicit
        // ",\n  PRIMARY KEY" append in buildDdl()) puts right after it,
        // breaking the DDL whenever this happens to be the last column of a
        // table with a primary key - so any explanatory note goes on its own
        // line above the field instead.
        String notePrefix;
        if (isDecimalHandledAsString(column)) {
            notePrefix = "  -- 源库类型为 " + column.mysqlType().toUpperCase(Locale.ROOT) + "，Debezium 连接器配置了 decimal.handling.mode=string，"
                + "该列在 Kafka 里被编码成十进制文本字符串而非标准 Avro decimal，这里必须按 STRING 声明（声明成 DECIMAL/INT/BIGINT 会被当作二进制 decimal 字节错误解析，读出乱码数值）；"
                + "写入数值类型的 sink 列时需要在 SELECT 里 CAST(`" + column.name() + "` AS " + mapType(column) + ")\n";
        } else if (mapKafkaSourceType(column).equals("STRING") && !isKnownStringType(column)) {
            notePrefix = "  -- 未识别的" + (column.oracle() ? "Oracle" : "MySQL") + "类型 " + column.mysqlType() + "，已按 STRING 处理，请手动确认\n";
        } else if (isDatetimeType(column)) {
            notePrefix = "  -- Debezium 把该字段编码成纪元毫秒整数，Flink debezium-avro-confluent 无法直接解析成 TIMESTAMP，"
                + "先按 BIGINT 存原始值，需要可读时间可在 SELECT 里用 TO_TIMESTAMP_LTZ(`" + column.name() + "`, 3) 转换\n";
        } else {
            notePrefix = "";
        }
        return notePrefix + "  " + quoteIdentifier(column.name()) + " " + flinkType;
    }

    private boolean isDatetimeType(ColumnInfo column) {
        String type = column.mysqlType().toLowerCase(Locale.ROOT);
        if (column.oracle()) {
            return type.equals("date") || type.startsWith("timestamp");
        }
        return switch (type) {
            case "datetime", "timestamp" -> true;
            default -> false;
        };
    }

    private boolean isKnownStringType(ColumnInfo column) {
        String type = column.mysqlType().toLowerCase(Locale.ROOT);
        if (column.oracle()) {
            return switch (type) {
                case "varchar2", "nvarchar2", "char", "nchar", "clob", "nclob", "long" -> true;
                default -> false;
            };
        }
        return switch (type) {
            case "varchar", "char", "text", "tinytext", "mediumtext", "longtext", "json", "enum", "set" -> true;
            default -> false;
        };
    }

    /**
     * The column's "true" type - DECIMAL/INT/BIGINT for a source
     * DECIMAL/NUMBER column - used for the physical ClickHouse/Doris/MySQL/
     * Oracle sink table's own CREATE TABLE (see SinkTableDdlBuilder) and as
     * the CAST target in generated SELECTs. Do NOT use this for a
     * Kafka-topic-backed table's CREATE TABLE (the CDC source table, or the
     * upsert-kafka skeleton sink below) - see mapKafkaSourceType().
     */
    public String mapType(ColumnInfo column) {
        return column.oracle() ? mapOracleType(column) : mapMysqlType(column);
    }

    /**
     * The type to declare for a column in any Kafka-topic-backed Flink
     * table (the CDC source table's 'connector'='kafka' WITH-clause, and the
     * upsert-kafka skeleton sink both come from columnDdlLine() below).
     * Every DECIMAL/NUMBER column source-side is forced to STRING here
     * regardless of what mapType() says, because every CdcSourceEntity's
     * Debezium connector is configured with decimal.handling.mode=string
     * (see DebeziumConnectorConfigBuilder) - Kafka Connect then encodes the
     * column as a plain Avro string carrying human-readable decimal text,
     * not the standard Avro "bytes + decimal logical type" encoding. If the
     * Flink column were declared DECIMAL/INT/BIGINT instead, Flink's
     * debezium-avro-confluent format reads that reader schema and tries to
     * decode the raw Avro string bytes as a binary fixed-point decimal,
     * producing garbage values (confirmed live: 59.90 came out as
     * 2285925972.96, 199.00 as 0) instead of a parse error.
     */
    public String mapKafkaSourceType(ColumnInfo column) {
        return isDecimalHandledAsString(column) ? "STRING" : mapType(column);
    }

    private boolean isDecimalHandledAsString(ColumnInfo column) {
        String type = column.mysqlType().toLowerCase(Locale.ROOT);
        if (column.oracle()) {
            return type.equals("number");
        }
        return type.equals("decimal") || type.equals("numeric");
    }

    private String mapMysqlType(ColumnInfo column) {
        String type = column.mysqlType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "bigint" -> "BIGINT";
            case "int", "integer", "mediumint" -> "INT";
            case "smallint" -> "SMALLINT";
            case "tinyint" -> column.columnType() != null && column.columnType().startsWith("tinyint(1)") ? "BOOLEAN" : "TINYINT";
            case "decimal", "numeric" -> "DECIMAL(" + (column.numericPrecision() == null ? 38 : column.numericPrecision())
                + ", " + (column.numericScale() == null ? 0 : column.numericScale()) + ")";
            case "float" -> "FLOAT";
            case "double" -> "DOUBLE";
            case "date" -> "DATE";
            case "time" -> "TIME";
            case "datetime", "timestamp" -> "BIGINT";
            case "boolean", "bool" -> "BOOLEAN";
            case "blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary" -> "BYTES";
            default -> "STRING";
        };
    }

    private String mapOracleType(ColumnInfo column) {
        String type = column.mysqlType().toLowerCase(Locale.ROOT);
        if (type.equals("number")) {
            Integer precision = column.numericPrecision();
            Integer scale = column.numericScale();
            // NUMBER with no precision/scale (a "bare" NUMBER) is Oracle's
            // arbitrary-precision numeric - DECIMAL(38, 10) is a generous,
            // Flink-representable ceiling for it, matching the same
            // "unspecified precision" fallback the MySQL branch already uses.
            if (precision == null) {
                return "DECIMAL(38, 10)";
            }
            if ((scale == null || scale == 0) && precision <= 9) {
                return "INT";
            }
            if ((scale == null || scale == 0) && precision <= 18) {
                return "BIGINT";
            }
            return "DECIMAL(" + precision + ", " + (scale == null ? 0 : scale) + ")";
        }
        return switch (type) {
            case "float", "binary_float" -> "FLOAT";
            case "binary_double" -> "DOUBLE";
            // Not a separate ANSI DATE case: Oracle's DATE always includes a
            // time-of-day component, and Debezium encodes it exactly like
            // TIMESTAMP - as io.debezium.time.Timestamp (epoch millis), not
            // an epoch-days int or ISO date string, regardless of which
            // converter (JSON or Avro) Kafka Connect serializes it with.
            // Declaring the Flink column DATE here used to submit fine but
            // fail every record at runtime (confirmed live, back when this
            // was still debezium-json) since Flink's format can't parse a
            // millis int64 as DATE. BIGINT matches what's actually on the
            // wire, same as the timestamp* branch below and MySQL's
            // datetime/timestamp handling in mapMysqlType().
            case "date" -> "BIGINT";
            default -> type.startsWith("timestamp") ? "BIGINT" : "STRING";
        };
    }

    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    public record ColumnInfo(String name, String mysqlType, String columnType, Integer numericPrecision, Integer numericScale, boolean oracle) {
    }

    public record TableSchema(List<ColumnInfo> columns, List<String> primaryKeys) {
    }

    public record TableDdl(List<String> columns, List<String> primaryKeys, boolean hasPrimaryKey, String ddl) {
    }
}
