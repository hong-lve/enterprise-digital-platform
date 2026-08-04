CREATE TABLE platform_lease (
  lock_name VARCHAR(160) PRIMARY KEY,
  lock_owner VARCHAR(64) NULL,
  lease_until DATETIME(3) NULL,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE job_operation_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  idempotency_key VARCHAR(128) NOT NULL,
  entity_type VARCHAR(30) NOT NULL,
  entity_id BIGINT NOT NULL,
  operation_type VARCHAR(30) NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'RUNNING/SUCCEEDED/FAILED',
  fencing_token BIGINT NOT NULL,
  error_message VARCHAR(1000) NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3) NULL,
  UNIQUE KEY uk_job_operation_idempotency (idempotency_key),
  INDEX idx_job_operation_entity (entity_type, entity_id, started_at)
);

ALTER TABLE flink_stream_job
  ADD COLUMN deployment_operation VARCHAR(30) NULL AFTER deployment_message,
  ADD COLUMN pending_resume_path VARCHAR(500) NULL AFTER deployment_operation,
  ADD COLUMN deployment_updated_at DATETIME(3) NULL AFTER pending_resume_path;

ALTER TABLE flink_sql_job
  ADD COLUMN deployment_operation VARCHAR(30) NULL AFTER deployment_message,
  ADD COLUMN pending_resume_path VARCHAR(500) NULL AFTER deployment_operation,
  ADD COLUMN deployment_updated_at DATETIME(3) NULL AFTER pending_resume_path;
