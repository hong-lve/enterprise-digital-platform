package com.company.dataops.console.service.kafka;

import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.entity.DataSourceEntity;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds a Debezium connector config (the JSON body Kafka Connect's PUT
 * /connectors/{name}/config expects) from a saved CdcSourceEntity plus the
 * DataSourceEntity its dataSourceId references (must be type=MYSQL or
 * type=ORACLE, enforced by the caller - see CdcSourceController).
 */
@Component
public class DebeziumConnectorConfigBuilder {
    // schema.history.internal.kafka.bootstrap.servers below is read by Kafka
    // Connect itself (this config is handed to it, not consumed by this
    // JVM), so it needs Kafka's docker-network address, not the host-facing
    // one this service's own Kafka clients use - confirmed live, connectors
    // built with the host-facing address sat in RESTARTING/FAILED with their
    // schema-history producer/consumer unable to resolve "localhost" from
    // inside the kafka-connect container.
    private final String kafkaBootstrapServers;

    public DebeziumConnectorConfigBuilder(
            @Value("${platform.bigdata.kafka-bootstrap-servers-internal:kafka:9092}") String kafkaBootstrapServers) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    public Map<String, Object> build(CdcSourceEntity source, DataSourceEntity dataSource) {
        String type = dataSource.getType() == null ? "" : dataSource.getType().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "MYSQL" -> buildMysql(source, dataSource);
            case "ORACLE" -> buildOracle(source, dataSource);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持作为 CDC 数据源的类型：" + dataSource.getType());
        };
    }

    private Map<String, Object> buildMysql(CdcSourceEntity source, DataSourceEntity dataSource) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        // flinkHost/flinkPort, not host/port - Kafka Connect runs inside
        // Docker same as the jobmanager/taskmanager flinkHost already serves
        // for sink targets, so it needs the same docker-network address, not
        // this JVM's own host/port. The entity javadoc's claim that
        // host.docker.internal "resolves identically from both the host JVM
        // and containers" doesn't hold on this Windows/Docker Desktop setup -
        // confirmed live: a MySQL data source registered with
        // host=host.docker.internal let the connector reach RUNNING, but
        // broke this JVM's own schema-reading calls (CdcTableSchemaService,
        // SinkTableDdlBuilder) with "Communications link failure", and
        // host=localhost did the reverse. Using flinkHost/flinkPort here
        // (docker-network address, e.g. "mysql") lets host/port stay
        // "localhost" for this JVM's own use, matching how buildOracle()
        // already does it below.
        config.put("database.hostname", dataSource.getFlinkHost());
        config.put("database.port", String.valueOf(dataSource.getFlinkPort()));
        config.put("database.user", dataSource.getUsername());
        config.put("database.password", dataSource.getPassword());
        // Debezium requires a numeric id unique among anything reading this
        // MySQL instance's binlog (acts like a MySQL replica server id).
        config.put("database.server.id", String.valueOf(100000 + source.getId()));
        config.put("topic.prefix", source.getTopicPrefix());
        config.put("database.include.list", source.getDatabaseName());
        config.put("table.include.list", source.getTableIncludeList());
        config.put("schema.history.internal.kafka.topic", "schema-changes." + source.getTopicPrefix());
        config.put("schema.history.internal.kafka.bootstrap.servers", kafkaBootstrapServers);
        config.put("include.schema.changes", "false");
        // Default (precise) mode encodes DECIMAL columns as base64 bytes via
        // Kafka Connect's Decimal logical type - Flink's debezium-json format
        // can't parse that back out and throws "Corrupt Debezium JSON
        // message". string mode emits a plain JSON string instead, which
        // Flink's format does understand.
        config.put("decimal.handling.mode", "string");
        return config;
    }

    private Map<String, Object> buildOracle(CdcSourceEntity source, DataSourceEntity dataSource) {
        if (dataSource.getFlinkHost() == null || dataSource.getFlinkHost().isBlank() || dataSource.getFlinkPort() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该 Oracle 数据源未配置 Flink 可达地址，Kafka Connect 也需要走这个地址连接（本环境里 Oracle 本身跑在 Docker 里，跟 ClickHouse/Doris 一样需要单独的容器可达地址）");
        }
        if (dataSource.getPdbName() == null || dataSource.getPdbName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该 Oracle 数据源未配置 PDB 名称，CDB 架构下 Debezium 需要知道具体监控哪个可插拔数据库");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("connector.class", "io.debezium.connector.oracle.OracleConnector");
        // Kafka Connect runs inside Docker, same reasoning as a sink target -
        // see DataSourceEntity.flinkHost's updated comment.
        config.put("database.hostname", dataSource.getFlinkHost());
        config.put("database.port", String.valueOf(dataSource.getFlinkPort()));
        config.put("database.user", dataSource.getUsername());
        config.put("database.password", dataSource.getPassword());
        // CDB root service name (e.g. "FREE") - LogMiner reads redo logs
        // shared across the whole CDB, so the JDBC connection targets the
        // root, then database.pdb.name tells Debezium which PDB's tables to
        // actually watch (confirmed live: this exact split is what got a
        // real connector to RUNNING against gvenzl/oracle-free).
        config.put("database.dbname", dataSource.getDatabaseName());
        config.put("database.pdb.name", dataSource.getPdbName());
        config.put("database.connection.adapter", "logminer");
        config.put("topic.prefix", source.getTopicPrefix());
        // Oracle's "schema" (owner) is the closest analogue to MySQL's
        // "database" - source.getDatabaseName() already means "which
        // database/schema to watch" for a MySQL CDC source, reused here with
        // the same meaning for Oracle rather than adding a parallel field.
        config.put("schema.include.list", source.getDatabaseName());
        config.put("table.include.list", source.getTableIncludeList());
        config.put("schema.history.internal.kafka.topic", "schema-changes." + source.getTopicPrefix());
        config.put("schema.history.internal.kafka.bootstrap.servers", kafkaBootstrapServers);
        // Same reasoning as MySQL's decimal.handling.mode above - Oracle
        // NUMBER columns hit the identical base64-vs-Flink-debezium-json
        // problem, confirmed live (this platform's demo table's NUMBER
        // columns came through as plain JSON strings with this set).
        config.put("decimal.handling.mode", "string");
        return config;
    }
}
