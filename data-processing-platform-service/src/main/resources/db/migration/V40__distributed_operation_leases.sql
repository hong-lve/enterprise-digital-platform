ALTER TABLE recovery_state
  MODIFY COLUMN entity_type VARCHAR(30) NOT NULL COMMENT 'CDC_SOURCE, FLINK_JOB or SQL_JOB';

ALTER TABLE flink_stream_job
  ADD COLUMN deployment_status VARCHAR(20) NULL COMMENT 'PREPARING/STOPPING/DEPLOYING/VERIFYING/RUNNING/ROLLBACK' AFTER status,
  ADD COLUMN deployment_message VARCHAR(500) NULL AFTER deployment_status;

ALTER TABLE alert_retry_queue
  ADD COLUMN lock_owner VARCHAR(64) NULL AFTER status,
  ADD COLUMN lock_until DATETIME NULL AFTER lock_owner,
  ADD INDEX idx_alert_retry_queue_lock (status, lock_until);

ALTER TABLE alert_retry_queue
  MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUCCEEDED/FAILED';

ALTER TABLE flink_stream_job
  ADD COLUMN schema_blocked TINYINT(1) NOT NULL DEFAULT 0 AFTER deployment_message,
  ADD COLUMN schema_block_reason VARCHAR(1000) NULL AFTER schema_blocked;

ALTER TABLE flink_sql_job
  ADD COLUMN deployment_status VARCHAR(20) NULL COMMENT 'PREPARING/STOPPING/DEPLOYING/VERIFYING/RUNNING/ROLLBACK' AFTER status,
  ADD COLUMN deployment_message VARCHAR(500) NULL AFTER deployment_status,
  ADD COLUMN schema_blocked TINYINT(1) NOT NULL DEFAULT 0 AFTER deployment_message,
  ADD COLUMN schema_block_reason VARCHAR(1000) NULL AFTER schema_blocked;

ALTER TABLE data_quality_violation
  ADD COLUMN run_id VARCHAR(36) NULL AFTER rule_id,
  ADD INDEX idx_data_quality_violation_run (rule_id, run_id, detected_at);
