package com.company.dataops.console.service.recovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.RecoveryEventEntity;
import com.company.dataops.console.entity.RecoveryStateEntity;
import com.company.dataops.console.mapper.RecoveryEventMapper;
import com.company.dataops.console.mapper.RecoveryStateMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Persistent, restart-surviving replacement for the old in-memory
 * CdcRecoveryTracker - state lives in recovery_state/recovery_event (MySQL),
 * not a ConcurrentHashMap, so a service restart mid-incident doesn't reset an
 * entity's retry budget back to a fresh slate (previously: a source stuck in
 * a crash loop across a service restart would silently get a fresh set of
 * attempts it hadn't earned). Generic over entity type/id so both
 * CdcSourceStatusScheduler and FlinkStreamJobPollingScheduler share one
 * implementation of the same tiered-retry/circuit-breaker/timeline logic
 * rather than each hand-rolling their own.
 *
 * Tiers escalate on repeated failure: quick retries first, then longer
 * backoff, then one last attempt far apart - once the last tier is exhausted
 * the circuit trips and nothing auto-retries again until a human calls
 * manualTakeover(). This is what "分级重试" + "恢复熔断" + "人工接管" in the
 * roadmap map to; recovery_event is the "完整恢复事件时间线".
 */
@Component
public class RecoveryOrchestrator {
    private static final Tier[] TIERS = {
        new Tier(3, 30),
        new Tier(3, 300),
        new Tier(2, 1800)
    };

    private final RecoveryStateMapper recoveryStateMapper;
    private final RecoveryEventMapper recoveryEventMapper;

    public RecoveryOrchestrator(RecoveryStateMapper recoveryStateMapper, RecoveryEventMapper recoveryEventMapper) {
        this.recoveryStateMapper = recoveryStateMapper;
        this.recoveryEventMapper = recoveryEventMapper;
    }

    /** Call once when an entity is first observed failed - not on every subsequent poll while it stays failed. */
    public void recordFailureDetected(String entityType, Long entityId, String entityName, String detail) {
        logEvent(entityType, entityId, entityName, "FAILURE_DETECTED", detail);
    }

    /** Whether it's worth attempting an automatic recovery action right now. */
    public boolean shouldAttempt(String entityType, Long entityId) {
        RecoveryStateEntity state = loadOrCreate(entityType, entityId);
        if ("TRIPPED".equals(state.getCircuitState())) {
            return false;
        }
        Tier tier = TIERS[state.getTier() - 1];
        if (state.getAttemptsInTier() >= tier.maxAttempts()) {
            if (state.getTier() >= TIERS.length) {
                state.setCircuitState("TRIPPED");
                recoveryStateMapper.updateById(state);
                logEvent(entityType, entityId, null, "CIRCUIT_TRIPPED", "已达到最高级别重试仍未恢复，自动恢复已停止，需要人工介入");
                return false;
            }
            state.setTier(state.getTier() + 1);
            state.setAttemptsInTier(0);
            recoveryStateMapper.updateById(state);
            logEvent(entityType, entityId, null, "TIER_ESCALATED", "升级到第 " + state.getTier() + " 级重试策略");
            tier = TIERS[state.getTier() - 1];
        }
        return state.getLastAttemptAt() == null || !LocalDateTime.now().isBefore(state.getLastAttemptAt().plusSeconds(tier.delaySeconds()));
    }

    /** Call right before actually issuing the recovery action (restart connector / resubmit job). */
    public void recordAttempt(String entityType, Long entityId, String entityName) {
        RecoveryStateEntity state = loadOrCreate(entityType, entityId);
        state.setAttemptsInTier(state.getAttemptsInTier() + 1);
        state.setLastAttemptAt(LocalDateTime.now());
        recoveryStateMapper.updateById(state);
        logEvent(entityType, entityId, entityName, "RETRY_ATTEMPTED", "第 " + state.getTier() + " 级重试，第 " + state.getAttemptsInTier() + " 次尝试");
    }

    /** Call once the entity is confirmed healthy again (self-healed, or a recovery attempt worked). */
    public void recordRecovered(String entityType, Long entityId, String entityName) {
        RecoveryStateEntity state = loadOrCreate(entityType, entityId);
        boolean wasDegraded = !"OK".equals(state.getCircuitState()) || state.getTier() > 1 || state.getAttemptsInTier() > 0;
        resetState(state);
        recoveryStateMapper.updateById(state);
        if (wasDegraded) {
            logEvent(entityType, entityId, entityName, "RECOVERED", null);
        }
    }

    /**
     * "人工接管": resets the circuit breaker and retry budget without implying
     * anything was actually recovered - used for intentional, non-failure
     * actions (a manual stop/pause) so a later, unrelated failure isn't
     * wrongly treated as still mid-incident.
     */
    public void reset(String entityType, Long entityId, String entityName, String reason) {
        RecoveryStateEntity state = loadOrCreate(entityType, entityId);
        resetState(state);
        recoveryStateMapper.updateById(state);
        logEvent(entityType, entityId, entityName, "RESET", reason);
    }

    /** Human explicitly takes over after a tripped circuit - re-enables automatic recovery. */
    public void manualTakeover(String entityType, Long entityId, String entityName, String operator) {
        RecoveryStateEntity state = loadOrCreate(entityType, entityId);
        resetState(state);
        recoveryStateMapper.updateById(state);
        logEvent(entityType, entityId, entityName, "MANUAL_TAKEOVER", operator + " 手动重置了恢复状态");
    }

    public RecoveryStateEntity state(String entityType, Long entityId) {
        return loadOrCreate(entityType, entityId);
    }

    public List<RecoveryEventEntity> timeline(String entityType, Long entityId) {
        return recoveryEventMapper.selectList(new LambdaQueryWrapper<RecoveryEventEntity>()
            .eq(RecoveryEventEntity::getEntityType, entityType)
            .eq(RecoveryEventEntity::getEntityId, entityId)
            .orderByDesc(RecoveryEventEntity::getOccurredAt));
    }

    private void resetState(RecoveryStateEntity state) {
        state.setTier(1);
        state.setAttemptsInTier(0);
        state.setCircuitState("OK");
        state.setLastAttemptAt(null);
    }

    private RecoveryStateEntity loadOrCreate(String entityType, Long entityId) {
        RecoveryStateEntity state = recoveryStateMapper.selectOne(new LambdaQueryWrapper<RecoveryStateEntity>()
            .eq(RecoveryStateEntity::getEntityType, entityType)
            .eq(RecoveryStateEntity::getEntityId, entityId));
        if (state != null) {
            return state;
        }
        RecoveryStateEntity fresh = new RecoveryStateEntity();
        fresh.setEntityType(entityType);
        fresh.setEntityId(entityId);
        fresh.setTier(1);
        fresh.setAttemptsInTier(0);
        fresh.setCircuitState("OK");
        recoveryStateMapper.insert(fresh);
        return fresh;
    }

    private void logEvent(String entityType, Long entityId, String entityName, String eventType, String detail) {
        RecoveryEventEntity event = new RecoveryEventEntity();
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setEntityName(entityName);
        event.setEventType(eventType);
        event.setDetail(detail);
        recoveryEventMapper.insert(event);
    }

    private record Tier(int maxAttempts, int delaySeconds) {
    }
}
