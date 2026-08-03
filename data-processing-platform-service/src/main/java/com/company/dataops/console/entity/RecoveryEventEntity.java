package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** One row per meaningful recovery-state transition - see RecoveryOrchestrator. */
@Data
@TableName("recovery_event")
public class RecoveryEventEntity {
    private Long id;
    private String entityType;
    private Long entityId;
    private String entityName;
    private String eventType;
    private String detail;
    private LocalDateTime occurredAt;
}
