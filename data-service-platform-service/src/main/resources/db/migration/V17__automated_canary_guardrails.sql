ALTER TABLE data_service_api_rollout
  ADD COLUMN automated TINYINT(1) NOT NULL DEFAULT 0 AFTER percentage,
  ADD COLUMN stage_plan_json JSON NULL AFTER automated,
  ADD COLUMN current_stage_index INT NOT NULL DEFAULT 0 AFTER stage_plan_json,
  ADD COLUMN stage_started_at DATETIME NULL AFTER current_stage_index,
  ADD COLUMN next_evaluation_at DATETIME NULL AFTER stage_started_at,
  ADD COLUMN health_policy_json JSON NULL AFTER next_evaluation_at,
  ADD COLUMN failure_action VARCHAR(20) NOT NULL DEFAULT 'PAUSE' AFTER health_policy_json,
  ADD COLUMN paused_reason VARCHAR(500) NULL AFTER failure_action,
  ADD COLUMN paused_by VARCHAR(80) NULL AFTER paused_reason,
  ADD COLUMN paused_at DATETIME NULL AFTER paused_by,
  ADD COLUMN lock_owner VARCHAR(100) NULL AFTER paused_at,
  ADD COLUMN lock_until DATETIME NULL AFTER lock_owner,
  DROP INDEX uk_api_rollout_active,
  DROP COLUMN active_api_id;

ALTER TABLE data_service_api_rollout
  ADD COLUMN active_api_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN status IN ('ACTIVE', 'PAUSED') THEN api_id ELSE NULL END
  ) STORED,
  ADD UNIQUE KEY uk_api_rollout_active (active_api_id),
  ADD KEY idx_api_rollout_due (status, automated, next_evaluation_at, lock_until);

CREATE TABLE data_service_api_rollout_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rollout_id BIGINT NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  stage_index INT NULL,
  percentage INT NULL,
  message VARCHAR(1000) NOT NULL,
  actor VARCHAR(80) NOT NULL,
  details_json JSON NULL,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_rollout_event_time (rollout_id, id)
);
