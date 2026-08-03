-- Checkpoint deep governance (highest-priority item of the reliability
-- hardening roadmap): FlinkStreamSubmissionClient.buildFlinkConfiguration()
-- previously only set execution.checkpointing.interval and restart-strategy -
-- every other checkpoint knob ran on Flink's own hardcoded defaults, with no
-- per-job visibility into checkpoint size/duration/failure history or which
-- savepoints exist and whether they're safe to resume from.
ALTER TABLE flink_stream_job
  ADD COLUMN checkpoint_timeout_ms INT NULL DEFAULT 600000 COMMENT 'execution.checkpointing.timeout',
  ADD COLUMN min_pause_between_checkpoints_ms INT NULL DEFAULT 0 COMMENT 'execution.checkpointing.min-pause',
  ADD COLUMN max_concurrent_checkpoints INT NULL DEFAULT 1 COMMENT 'execution.checkpointing.max-concurrent-checkpoints',
  ADD COLUMN tolerable_failed_checkpoints INT NULL DEFAULT 0 COMMENT 'execution.checkpointing.tolerable-failed-checkpoints',
  ADD COLUMN checkpointing_mode VARCHAR(20) NULL DEFAULT 'EXACTLY_ONCE' COMMENT 'EXACTLY_ONCE or AT_LEAST_ONCE',
  ADD COLUMN externalized_checkpoint_retention VARCHAR(30) NULL DEFAULT 'RETAIN_ON_CANCELLATION' COMMENT 'RETAIN_ON_CANCELLATION or DELETE_ON_CANCELLATION',
  ADD COLUMN unaligned_checkpoints_enabled TINYINT(1) NULL DEFAULT 0 COMMENT 'execution.checkpointing.unaligned.enabled',
  ADD COLUMN checkpoint_failure_alert_state VARCHAR(20) NULL DEFAULT 'OK' COMMENT 'mirrors backpressure_alert_state/consumer_lag_alert_state - managed by FlinkCheckpointHistoryScheduler',
  ADD COLUMN savepoint_retention_count INT NULL DEFAULT 5 COMMENT 'how many of this job''s most recent savepoints FlinkSavepointRetentionScheduler keeps before disposing older ones; NULL/0 disables auto-disposal';

-- Per-checkpoint history, populated by FlinkCheckpointHistoryScheduler polling
-- Flink's own GET /jobs/:id/checkpoints REST endpoint. One table backs both
-- the size/duration/failure trend view and the savepoint inventory (rows
-- where checkpoint_type is SAVEPOINT/SYNC_SAVEPOINT and external_path is set)
-- - a savepoint is just a checkpoint Flink happens to also keep forever and
-- expose an external_path for, not a separate concept at the REST level.
CREATE TABLE flink_checkpoint_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id BIGINT NOT NULL COMMENT 'flink_stream_job.id',
  -- The Flink-side job id at the time this checkpoint ran - a full job
  -- restart gets a brand new one, so history from an earlier instance stays
  -- correctly attributed to that instance rather than colliding.
  flink_job_id VARCHAR(64) NOT NULL,
  checkpoint_id BIGINT NOT NULL COMMENT 'Flink''s own monotonic checkpoint id, unique per flink_job_id',
  checkpoint_type VARCHAR(20) NOT NULL COMMENT 'CHECKPOINT, SAVEPOINT, or SYNC_SAVEPOINT',
  status VARCHAR(20) NOT NULL COMMENT 'IN_PROGRESS, COMPLETED, or FAILED',
  trigger_timestamp BIGINT NULL COMMENT 'epoch millis, from Flink',
  latest_ack_timestamp BIGINT NULL,
  end_to_end_duration_ms BIGINT NULL,
  state_size_bytes BIGINT NULL,
  external_path VARCHAR(500) NULL COMMENT 'set for SAVEPOINT/externalized CHECKPOINT entries - the path a future start() can resume from',
  failure_message VARCHAR(1000) NULL,
  disposed TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'true once FlinkSavepointRetentionScheduler (or a manual dispose action) has called Flink''s /savepoint-disposal for external_path',
  restore_outcome VARCHAR(20) NULL COMMENT 'set the next time a job actually resumes from this entry''s external_path: VERIFIED or FAILED',
  restore_checked_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_flink_checkpoint_history_job_checkpoint (job_id, flink_job_id, checkpoint_id),
  INDEX idx_flink_checkpoint_history_job (job_id, trigger_timestamp)
);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(111, 43, 'Flink 流作业-保存点管理', NULL, NULL, NULL, 'realtime:flink:checkpoint-manage', 'BUTTON', 7, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 111);
