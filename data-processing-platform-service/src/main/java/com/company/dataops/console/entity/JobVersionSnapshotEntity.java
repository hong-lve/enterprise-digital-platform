package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * An immutable, numbered config snapshot of a Flink stream job or SQL job -
 * see JobVersionSnapshotService, the only class that writes these. Keyed by
 * (entityType, entityId) rather than a foreign key to either job table,
 * same generic-entity pattern as RecoveryStateEntity/RecoveryEventEntity.
 */
@Data
@TableName("job_version_snapshot")
public class JobVersionSnapshotEntity {
    private Long id;
    private String entityType;
    private Long entityId;
    private Integer versionNo;
    private String configJson;
    private String savepointPath;
    private String flinkJobId;
    private String changeSummary;
    private Integer rollbackOfVersion;
    private String createdBy;
    private LocalDateTime createdAt;
}
