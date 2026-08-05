package com.company.dataops.console.service.flink;

import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.service.datasource.DataSourceConnectionService;
import com.company.dataops.console.service.kafka.CdcTableSchemaService;
import com.company.dataops.console.service.query.ColumnView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generates a ClickHouse/Doris/MySQL/Oracle sink CREATE TABLE + INSERT INTO block to
 * append after the Kafka source table the "建表向导" wizard already produces
 * (see CdcTableSchemaService.describeTable()) - the column set/types/primary
 * key come from the exact same source introspection, so the sink's column
 * list can never drift from the source's. Column *names*, though, are taken
 * from the physical target table where it already exists (see
 * resolveSinkColumnNames()), since those are what the sink connector writes
 * with and their casing need not match the source's. WITH-clause shapes below
 * match the ones hand-verified running end-to-end earlier this project (jobs
 * sql-job-demo-source-to-clickhouse/-to-doris) - not a new, untested format.
 */
@Component
public class SinkTableDdlBuilder {
    /** Doris Stream Load rejects any label longer than this - see dorisWithClause(). */
    private static final int DORIS_LABEL_MAX_LENGTH = 128;

    private final CdcTableSchemaService cdcTableSchemaService;
    private final DataSourceConnectionService dataSourceConnectionService;

    public SinkTableDdlBuilder(CdcTableSchemaService cdcTableSchemaService,
                               DataSourceConnectionService dataSourceConnectionService) {
        this.cdcTableSchemaService = cdcTableSchemaService;
        this.dataSourceConnectionService = dataSourceConnectionService;
    }

    public String build(CdcSourceEntity cdcSource, DataSourceEntity cdcDataSource, String qualifiedTable, DataSourceEntity target, String targetTable) {
        if (!cdcTableSchemaService.listTables(cdcSource).contains(qualifiedTable)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该表不在这个 CDC 源声明的表清单里：" + qualifiedTable);
        }
        String tableName = cdcTableSchemaService.tableNamePart(qualifiedTable);
        CdcTableSchemaService.TableSchema schema = cdcTableSchemaService.readTableSchema(
            cdcDataSource, cdcTableSchemaService.schemaPart(qualifiedTable), tableName);
        if (schema.columns().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有读到任何字段，确认表是否存在：" + qualifiedTable);
        }
        if (schema.primaryKeys().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该表没有主键，无法生成 sink（ClickHouse/Doris/MySQL sink 都要求声明主键）");
        }

        String type = target.getType() == null ? "" : target.getType().toUpperCase(Locale.ROOT);
        String withClause = switch (type) {
            case "CLICKHOUSE" -> jdbcWithClause("jdbc:clickhouse", target, targetTable);
            case "MYSQL" -> jdbcWithClause("jdbc:mysql", target, targetTable);
            case "ORACLE" -> oracleWithClause(target, targetTable);
            case "DORIS" -> dorisWithClause(target, targetTable);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持作为 sink 目标的数据源类型：" + target.getType());
        };

        Function<String, String> sinkColumnName = resolveSinkColumnNames(target, targetTable, schema);

        String srcName = tableName + "_src";
        String sinkName = tableName + "_" + sinkSuffix(type);
        String columnLines = schema.columns().stream()
            .map(column -> "  " + cdcTableSchemaService.quoteIdentifier(sinkColumnName.apply(column.name()))
                + " " + cdcTableSchemaService.mapType(column))
            .collect(Collectors.joining(",\n"));
        String selectColumns = schema.columns().stream()
            .map(this::selectExpression)
            .collect(Collectors.joining(", "));
        String primaryKeyColumns = schema.primaryKeys().stream()
            .map(sinkColumnName)
            .collect(Collectors.joining(", "));

        StringBuilder ddl = new StringBuilder();
        ddl.append("\nCREATE TABLE ").append(sinkName).append(" (\n");
        ddl.append(columnLines).append(",\n");
        ddl.append("  PRIMARY KEY (").append(primaryKeyColumns).append(") NOT ENFORCED\n");
        ddl.append(") WITH (\n").append(withClause).append("\n);\n\n");
        ddl.append("INSERT INTO ").append(sinkName).append(" SELECT ").append(selectColumns).append(" FROM ").append(srcName).append(";\n");
        return ddl.toString();
    }

    /**
     * Maps a source column name to the name the physical sink table actually
     * uses, so the generated sink table can be declared against a target whose
     * identifier casing differs from the source's.
     *
     * Needed because a Flink JDBC sink sends the Flink table's column names
     * verbatim as the DB column names, and Oracle reports its identifiers
     * uppercase while a sink table is typically created lowercase - against
     * ClickHouse, whose column names are case-sensitive, every insert then
     * failed with "Writing records to JDBC failed" while the job still showed
     * RUNNING. MySQL-sourced jobs never hit it only because MySQL's lowercase
     * names happened to match.
     *
     * Falls back to the source names when the target table returns no columns
     * at all, which means it simply hasn't been created yet - a legitimate way
     * to use this endpoint (generate the DDL first, create the table after).
     * A target that does exist but is missing a column is a different story:
     * that INSERT can only fail at runtime, so it's rejected here instead.
     */
    private Function<String, String> resolveSinkColumnNames(DataSourceEntity target, String targetTable,
                                                            CdcTableSchemaService.TableSchema schema) {
        List<ColumnView> targetColumns = dataSourceConnectionService.columns(target, target.getDatabaseName(), targetTable);
        if (targetColumns.isEmpty()) {
            return Function.identity();
        }
        Map<String, String> byLowerName = new LinkedHashMap<>();
        for (ColumnView column : targetColumns) {
            byLowerName.put(column.name().toLowerCase(Locale.ROOT), column.name());
        }
        List<String> missing = new ArrayList<>();
        for (CdcTableSchemaService.ColumnInfo column : schema.columns()) {
            if (!byLowerName.containsKey(column.name().toLowerCase(Locale.ROOT))) {
                missing.add(column.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "目标表 " + targetTable + " 缺少源表的这些字段：" + String.join("、", missing)
                    + "；请先补齐目标表结构，否则作业能提交但写入时必然失败");
        }
        return sourceName -> byLowerName.getOrDefault(sourceName.toLowerCase(Locale.ROOT), sourceName);
    }

    /**
     * The Kafka source table (see CdcTableSchemaService.mapKafkaSourceType())
     * declares DECIMAL/NUMBER columns as STRING - Debezium's
     * decimal.handling.mode=string encodes them as decimal text, not
     * standard Avro decimal - but this sink's own CREATE TABLE above still
     * declares them as the real numeric type via cdcTableSchemaService.mapType(),
     * matching the physical ClickHouse/Doris/MySQL/Oracle column. Passing the
     * raw STRING value straight into a numeric sink column would either fail
     * to type-check or (for connectors that do accept it) write garbage, so
     * any column where the two types disagree needs an explicit CAST back to
     * the numeric type here.
     */
    private String selectExpression(CdcTableSchemaService.ColumnInfo column) {
        String identifier = cdcTableSchemaService.quoteIdentifier(column.name());
        String sourceType = cdcTableSchemaService.mapKafkaSourceType(column);
        String targetType = cdcTableSchemaService.mapType(column);
        if (sourceType.equals(targetType)) {
            return identifier;
        }
        return "CAST(" + identifier + " AS " + targetType + ") AS " + identifier;
    }

    private String sinkSuffix(String type) {
        return switch (type) {
            case "CLICKHOUSE" -> "ch_sink";
            case "DORIS" -> "doris_sink";
            case "MYSQL" -> "mysql_sink";
            case "ORACLE" -> "oracle_sink";
            default -> "sink";
        };
    }

    private String jdbcWithClause(String jdbcScheme, DataSourceEntity target, String targetTable) {
        requireFlinkAddress(target, false);
        requireDatabaseName(target);
        return "  'connector' = 'jdbc',\n"
            + "  'url' = '" + jdbcScheme + "://" + target.getFlinkHost() + ":" + target.getFlinkPort() + "/" + target.getDatabaseName() + "',\n"
            + "  'table-name' = '" + targetTable + "',\n"
            + "  'username' = '" + target.getUsername() + "',\n"
            + "  'password' = '" + secretReference(target) + "'";
    }

    private String oracleWithClause(DataSourceEntity target, String targetTable) {
        // Oracle's JDBC URL shape (jdbc:oracle:thin:@//host:port/service) is
        // its own format, not the generic "scheme://host:port/db" the other
        // jdbcWithClause() targets share - matches
        // DataSourceConnectionService.buildJdbcUrl()'s ORACLE case.
        requireFlinkAddress(target, false);
        requireDatabaseName(target);
        return "  'connector' = 'jdbc',\n"
            + "  'url' = 'jdbc:oracle:thin:@//" + target.getFlinkHost() + ":" + target.getFlinkPort() + "/" + target.getDatabaseName() + "',\n"
            + "  'table-name' = '" + targetTable + "',\n"
            + "  'username' = '" + target.getUsername() + "',\n"
            + "  'password' = '" + secretReference(target) + "'";
    }

    private String dorisWithClause(DataSourceEntity target, String targetTable) {
        requireFlinkAddress(target, true);
        requireDatabaseName(target);
        // A label prefix only needs to be unique per sink table, not per run
        // - baking a random suffix in once at DDL-generation time (rather
        // than regenerating on every job start) has already been verified
        // safe to stop/restart repeatedly against the same Doris table.
        //
        // The prefix has to leave room for what the Doris connector appends to
        // it before handing the result to Stream Load, which enforces
        // `^[-_A-Za-z0-9:]{1,128}$` on the FULL label. DorisWriter builds
        // `{prefix}_{db}_{table}_{subtask}_{checkpoint}_{uuid}`, so the
        // suffix alone costs db+table+~44 characters. Confirmed live: target
        // table `test_orders_mysql_doris_sql_sink` in database
        // `realtime_demo` produced a 129-character label and every checkpoint
        // failed with `Label format error` - one character over the limit,
        // with no invalid characters involved. The job still reported RUNNING
        // because the Doris sink only commits on checkpoint, so the symptom
        // was a permanently empty sink table rather than a failed job.
        //
        // Budget the prefix against the actual database/table names rather
        // than a fixed guess, and keep the random suffix (uniqueness matters
        // more than readability when the name has to be truncated).
        //
        // The measured suffix is db + table + 43 (5 separators + a 36-char
        // UUID + a single-digit subtask and checkpoint id). Budget db + table
        // + 64 instead: the subtask index grows with parallelism and the
        // checkpoint id grows without bound, so a budget that only just fits
        // checkpoint 1 would silently start failing hours into a long-running
        // job. The extra 21 characters cover a 3-digit subtask and a 10-digit
        // checkpoint id with room to spare.
        String randomSuffix = "-" + UUID.randomUUID().toString().substring(0, 8);
        int connectorSuffixBudget = target.getDatabaseName().length() + targetTable.length() + 64;
        int room = Math.max(1, DORIS_LABEL_MAX_LENGTH - connectorSuffixBudget - randomSuffix.length());
        String labelBase = targetTable.length() > room ? targetTable.substring(0, room) : targetTable;
        String labelPrefix = labelBase + randomSuffix;
        return "  'connector' = 'doris',\n"
            + "  'fenodes' = '" + target.getFlinkHost() + ":" + target.getFlinkHttpPort() + "',\n"
            + "  'table.identifier' = '" + target.getDatabaseName() + "." + targetTable + "',\n"
            + "  'username' = '" + target.getUsername() + "',\n"
            + "  'password' = '" + secretReference(target) + "',\n"
            + "  'sink.label-prefix' = '" + labelPrefix + "',\n"
            + "  'sink.enable-delete' = 'true'";
    }

    private void requireFlinkAddress(DataSourceEntity target, boolean needsHttpPort) {
        boolean missing = target.getFlinkHost() == null || target.getFlinkHost().isBlank() || target.getFlinkPort() == null
            || (needsHttpPort && target.getFlinkHttpPort() == null);
        if (missing) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该数据源未配置 Flink 可达地址，请先在数据源管理里补充");
        }
    }

    private void requireDatabaseName(DataSourceEntity target) {
        if (target.getDatabaseName() == null || target.getDatabaseName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该数据源未配置默认数据库，请先在数据源管理里补充");
        }
    }

    private String secretReference(DataSourceEntity target) {
        if (target.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标数据源必须先保存，才能生成安全的凭据引用");
        }
        return "${secret:datasource:" + target.getId() + ":password}";
    }
}
