package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** Last-seen Schema Registry version per (cdc_source, subject) - see SchemaDriftScheduler. */
@Data
@TableName("schema_snapshot")
public class SchemaSnapshotEntity {
    private Long id;
    private Long cdcSourceId;
    private String subject;
    private Integer lastSeenVersion;
    private String lastCompatibilityResult;
    private LocalDateTime checkedAt;
}
