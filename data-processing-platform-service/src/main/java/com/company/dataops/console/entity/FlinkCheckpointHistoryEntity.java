package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * One row per checkpoint (regular or savepoint) Flink has ever reported for a
 * flink_stream_job, populated by FlinkCheckpointHistoryScheduler polling
 * Flink's own GET /jobs/:id/checkpoints REST endpoint. Backs both the
 * checkpoint size/duration/failure trend view and the savepoint inventory
 * (rows where checkpointType is SAVEPOINT/SYNC_SAVEPOINT) - a savepoint is
 * just a checkpoint Flink also exposes an externalPath for, not a distinct
 * REST concept.
 */
@Data
@TableName("flink_checkpoint_history")
public class FlinkCheckpointHistoryEntity {
    private Long id;
    private Long jobId;
    // The Flink-side job id at the time this checkpoint ran - a full job
    // restart gets a brand new one, so history from an earlier instance
    // stays correctly attributed rather than colliding on checkpointId.
    private String flinkJobId;
    private Long checkpointId;
    private String checkpointType;
    private String status;
    private Long triggerTimestamp;
    private Long latestAckTimestamp;
    private Long endToEndDurationMs;
    private Long stateSizeBytes;
    private String externalPath;
    private String failureMessage;
    private Boolean disposed;
    private String restoreOutcome;
    private LocalDateTime restoreCheckedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
