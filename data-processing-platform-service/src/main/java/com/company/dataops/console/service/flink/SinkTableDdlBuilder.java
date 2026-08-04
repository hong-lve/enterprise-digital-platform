package com.company.dataops.console.service.flink;

import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.service.kafka.CdcTableSchemaService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generates a ClickHouse/Doris/MySQL/Oracle sink CREATE TABLE + INSERT INTO block to
 * append after the Kafka source table the "建表向导" wizard already produces
 * (see CdcTableSchemaService.describeTable()) - the column list/types/primary
 * key come from the exact same MySQL introspection, so the sink's column
 * list can never drift from the source's. WITH-clause shapes below match the
 * ones hand-verified running end-to-end earlier this project (jobs
 * sql-job-demo-source-to-clickhouse/-to-doris) - not a new, untested format.
 */
@Component
public class SinkTableDdlBuilder {
    private final CdcTableSchemaService cdcTableSchemaService;

    public SinkTableDdlBuilder(CdcTableSchemaService cdcTableSchemaService) {
        this.cdcTableSchemaService = cdcTableSchemaService;
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
            case "REDIS" -> redisWithClause(target, targetTable);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持作为 sink 目标的数据源类型：" + target.getType());
        };

        String srcName = tableName + "_src";
        String sinkName = tableName + "_" + sinkSuffix(type);
        String columnLines = schema.columns().stream()
            .map(column -> "  " + cdcTableSchemaService.quoteIdentifier(column.name()) + " " + cdcTableSchemaService.mapType(column))
            .collect(Collectors.joining(",\n"));
        String selectColumns = schema.columns().stream()
            .map(this::selectExpression)
            .collect(Collectors.joining(", "));

        StringBuilder ddl = new StringBuilder();
        ddl.append("\nCREATE TABLE ").append(sinkName).append(" (\n");
        ddl.append(columnLines).append(",\n");
        ddl.append("  PRIMARY KEY (").append(String.join(", ", schema.primaryKeys())).append(") NOT ENFORCED\n");
        ddl.append(") WITH (\n").append(withClause).append("\n);\n\n");
        ddl.append("INSERT INTO ").append(sinkName).append(" SELECT ").append(selectColumns).append(" FROM ").append(srcName).append(";\n");
        return ddl.toString();
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
            case "REDIS" -> "redis_sink";
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
            + "  'password' = '" + target.getPassword() + "'";
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
            + "  'password' = '" + target.getPassword() + "'";
    }

    private String dorisWithClause(DataSourceEntity target, String targetTable) {
        requireFlinkAddress(target, true);
        requireDatabaseName(target);
        // A label prefix only needs to be unique per sink table, not per run
        // - baking a random suffix in once at DDL-generation time (rather
        // than regenerating on every job start) has already been verified
        // safe to stop/restart repeatedly against the same Doris table.
        String labelPrefix = targetTable + "-" + UUID.randomUUID().toString().substring(0, 8);
        return "  'connector' = 'doris',\n"
            + "  'fenodes' = '" + target.getFlinkHost() + ":" + target.getFlinkHttpPort() + "',\n"
            + "  'table.identifier' = '" + target.getDatabaseName() + "." + targetTable + "',\n"
            + "  'username' = '" + target.getUsername() + "',\n"
            + "  'password' = '" + (target.getPassword() == null ? "" : target.getPassword()) + "',\n"
            + "  'sink.label-prefix' = '" + labelPrefix + "',\n"
            + "  'sink.enable-delete' = 'true'";
    }

    private String redisWithClause(DataSourceEntity target, String targetTable) {
        // Redis isn't JDBC-queryable, so this doesn't share jdbcWithClause()
        // - see flink-connectors/redis-table-sink for the connector itself.
        // key-prefix namespaces by target table so two sink tables writing
        // into the same Redis instance don't collide on the same keys.
        requireFlinkAddress(target, false);
        String password = target.getPassword() == null ? "" : target.getPassword();
        return "  'connector' = 'redis',\n"
            + "  'host' = '" + target.getFlinkHost() + "',\n"
            + "  'port' = '" + target.getFlinkPort() + "',\n"
            + "  'password' = '" + password + "',\n"
            + "  'key-prefix' = '" + targetTable + ":'";
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
}
