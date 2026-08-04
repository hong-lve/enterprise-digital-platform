package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * A Flink streaming job defined by SQL (CREATE TABLE(s) + one INSERT INTO)
 * instead of a jar - see FlinkStreamJobEntity for the jar-based equivalent.
 * Source is always Kafka; sink can be Kafka, ClickHouse
 * ('connector'='jdbc', see flink-connectors/clickhouse-jdbc-dialect), or
 * Doris ('connector'='doris', official flink-doris-connector - unlike
 * ClickHouse, Doris's Unique Key model does real upsert/delete itself, so
 * it doesn't need the aggregate-before-sink workaround ClickHouse does).
 * No restartStrategy fields (confirmed live that Flink SQL Gateway session
 * properties don't actually apply a custom restart strategy - see
 * V10__flink_sql_job.sql).
 */
@Data
@TableName("flink_sql_job")
public class FlinkSqlJobEntity {
    private Long id;
    @NotBlank(message = "名称不能为空")
    private String name;
    @NotBlank(message = "SQL 不能为空")
    private String sqlScript;
    @NotNull(message = "并行度不能为空")
    private Integer parallelism;
    @NotNull(message = "Checkpoint 间隔不能为空")
    private Integer checkpointIntervalMs;
    private String flinkJobId;
    private String savepointPath;
    private String status;
    private String deploymentStatus;
    private String deploymentMessage;
    private String deploymentOperation;
    private String pendingResumePath;
    private LocalDateTime deploymentUpdatedAt;
    private Boolean schemaBlocked;
    private String schemaBlockReason;
    private String lastError;
    private String alertState;
    private Double backpressureRatio;
    private String backpressureAlertState;
    private String kafkaConsumerGroupId;
    private String kafkaTopics;
    private Long consumerLagRecords;
    private String consumerLagAlertState;
    // Optional, comma-separated - see V11__flink_sql_job_clickhouse_sink.sql,
    // V12__flink_sql_job_doris_sink.sql, V19__flink_sql_job_oracle_sink.sql
    // and V21__flink_sql_job_redis_sink.sql.
    private String clickhouseSinkTables;
    private String dorisSinkTables;
    private String oracleSinkTables;
    private String redisSinkTables;
    private String environment;
    private Long clusterId;
    private String owner;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
