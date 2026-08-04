package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Current tiered-retry/circuit-breaker state for one recoverable entity
 * (a CDC source or a Flink job), keyed by (entityType, entityId) rather than
 * a foreign key to either table - see RecoveryOrchestrator, the only class
 * that reads/writes this.
 */
@Data
@TableName("recovery_state")
public class RecoveryStateEntity {
    private Long id;
    private String entityType;
    private Long entityId;
    private Integer tier;
    private Integer attemptsInTier;
    private LocalDateTime lastAttemptAt;
    private String circuitState;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime updatedAt;
}
