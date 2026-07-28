-- Flink streaming job lifecycle management: unlike a batch job, a streaming
-- job normally never reaches a terminal "success" state - it just keeps
-- RUNNING until manually stopped (stop-with-savepoint, so the next start can
-- resume from where it left off) or it fails.

CREATE TABLE flink_stream_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  jar_path VARCHAR(500) NOT NULL,
  entry_class VARCHAR(300) NULL,
  program_args VARCHAR(1000) NULL,
  parallelism INT NOT NULL DEFAULT 1,
  checkpoint_interval_ms INT NOT NULL DEFAULT 10000,
  restart_strategy VARCHAR(20) NOT NULL DEFAULT 'FIXED_DELAY',
  restart_attempts INT NOT NULL DEFAULT 3,
  restart_delay_seconds INT NOT NULL DEFAULT 10,
  flink_job_id VARCHAR(64) NULL,
  savepoint_path VARCHAR(500) NULL COMMENT 'saved on stop; next start resumes from here if present',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/FAILED/CANCELED/FINISHED',
  last_error TEXT NULL,
  owner VARCHAR(100),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
