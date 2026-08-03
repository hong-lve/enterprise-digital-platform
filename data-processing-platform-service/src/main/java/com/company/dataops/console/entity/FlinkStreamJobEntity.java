package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("flink_stream_job")
public class FlinkStreamJobEntity {
    private Long id;
    @NotBlank(message = "名称不能为空")
    private String name;
    @NotBlank(message = "JAR 路径不能为空")
    private String jarPath;
    private String entryClass;
    private String programArgs;
    @NotNull(message = "并行度不能为空")
    private Integer parallelism;
    @NotNull(message = "Checkpoint 间隔不能为空")
    private Integer checkpointIntervalMs;
    @NotBlank(message = "重启策略不能为空")
    private String restartStrategy;
    private Integer restartAttempts;
    private Integer restartDelaySeconds;
    // Checkpoint governance - see FlinkStreamSubmissionClient.buildFlinkConfiguration()
    // for how these map to execution.checkpointing.* Flink config keys. All
    // nullable with server-side defaults applied at config-build time (not
    // column DEFAULTs read back into the entity), same pattern as
    // checkpointIntervalMs/restartStrategy above.
    private Integer checkpointTimeoutMs;
    private Integer minPauseBetweenCheckpointsMs;
    private Integer maxConcurrentCheckpoints;
    private Integer tolerableFailedCheckpoints;
    private String checkpointingMode;
    private String externalizedCheckpointRetention;
    private Boolean unalignedCheckpointsEnabled;
    private String checkpointFailureAlertState;
    private Integer savepointRetentionCount;
    private String flinkJobId;
    private String savepointPath;
    private String status;
    private String lastError;
    private String alertState;
    private Double backpressureRatio;
    private String backpressureAlertState;
    // Optional - only the person submitting the job knows what its jar
    // actually consumes; blank skips consumer-lag monitoring entirely.
    private String kafkaConsumerGroupId;
    private String kafkaTopics;
    private Long consumerLagRecords;
    private String consumerLagAlertState;
    // Optional - the ClickHouse table(s) this job writes to, for the
    // lineage view (LineageController). Same reasoning as kafkaTopics: the
    // platform can't infer this from the jar.
    private String clickhouseSinkTables;
    // Optional - DEV/STAGING/PROD, server-defaults to DEV if blank (see
    // FlinkStreamJobController.create()). Logical tag only, not physical
    // isolation - see V9__environment_field.sql. Gates start/stop/delete/
    // update via EnvironmentGuard when set to PROD.
    private String environment;
    private String owner;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
