-- Persistent auto-recovery (2nd item of the reliability-hardening roadmap):
-- CdcRecoveryTracker previously kept every CDC source's recovery-attempt
-- count in a ConcurrentHashMap - a service restart mid-incident silently
-- reset a source's retry budget back to a fresh slate, and Flink jobs had no
-- automatic recovery attempt at all (FlinkStreamJobPollingScheduler only
-- polls RUNNING jobs, so a FAILED job dropped out of every future poll
-- forever - same bug class CdcSourceStatusScheduler's own query was fixed
-- for earlier). recovery_state/recovery_event replace that in-memory
-- tracker with a durable, entity-type-agnostic tiered-retry/circuit-breaker
-- state machine shared by both CDC sources and Flink jobs.
CREATE TABLE recovery_state (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type VARCHAR(30) NOT NULL COMMENT 'CDC_SOURCE or FLINK_JOB',
  entity_id BIGINT NOT NULL,
  tier INT NOT NULL DEFAULT 1 COMMENT '1-3, escalates on repeated failure - see RecoveryOrchestrator.TIERS',
  attempts_in_tier INT NOT NULL DEFAULT 0,
  last_attempt_at DATETIME NULL,
  circuit_state VARCHAR(20) NOT NULL DEFAULT 'OK' COMMENT 'OK, or TRIPPED once tier 3 is exhausted - auto-recovery stops until a human calls manual-takeover',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_recovery_state_entity (entity_type, entity_id)
);

-- Append-only timeline - the "完整恢复事件时间线" requirement. One row per
-- meaningful transition (not every poll tick), so the drawer reads as a
-- readable incident history rather than a flood of identical entries.
CREATE TABLE recovery_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type VARCHAR(30) NOT NULL,
  entity_id BIGINT NOT NULL,
  entity_name VARCHAR(200) NULL,
  event_type VARCHAR(30) NOT NULL COMMENT 'FAILURE_DETECTED, RETRY_ATTEMPTED, TIER_ESCALATED, CIRCUIT_TRIPPED, RECOVERED, MANUAL_TAKEOVER, RESET',
  detail VARCHAR(1000) NULL,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_recovery_event_entity (entity_type, entity_id, occurred_at)
);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(112, 42, 'CDC 数据源-恢复管理', NULL, NULL, NULL, 'realtime:cdc:recovery-manage', 'BUTTON', 6, 'Y'),
(113, 43, 'Flink 流作业-恢复管理', NULL, NULL, NULL, 'realtime:flink:recovery-manage', 'BUTTON', 9, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 112), (1, 113);
