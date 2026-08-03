-- Tier 3 item 2 of the reliability roadmap ("作业版本快照、配置差异与快速回滚").
-- "灰度升级" in the literal sense (splitting live traffic across two running
-- instances) doesn't apply to a single-instance Flink job - by explicit user
-- choice this ships the practical equivalent instead: every definition
-- change (create/edit/rolling-upgrade) is recorded as an immutable, numbered
-- config snapshot that can be diffed field-by-field against any other
-- version and rolled back to with one action. No automatic canary/health-
-- check rollback - see [[project_reliability_hardening_roadmap]].

-- One shared table for both job kinds (entity_type/entity_id), same
-- generic-entity pattern already used by recovery_state/recovery_event -
-- the version/diff/rollback logic itself doesn't care which kind of job it
-- is, only the two controllers applying a rollback do.
CREATE TABLE job_version_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type VARCHAR(32) NOT NULL COMMENT 'FLINK_STREAM_JOB or FLINK_SQL_JOB',
  entity_id BIGINT NOT NULL,
  version_no INT NOT NULL COMMENT 'per entity_type+entity_id, starting at 1',
  -- The job's own definition fields (jar/SQL, parallelism, checkpoint
  -- config, sink lists, environment, ...) as JSON - runtime-only fields
  -- (flinkJobId/status/lastError/alertState/backpressure*/consumerLag*) are
  -- nulled out before serializing so they never show up as noise in a diff.
  config_json TEXT NOT NULL,
  -- The savepoint this exact version was (or, for a not-yet-deployed
  -- version, would be) resumed from - null means a fresh start. Recorded
  -- separately from config_json since it's the one piece of this version's
  -- deployment history a rollback actually needs to reuse.
  savepoint_path VARCHAR(500) NULL,
  flink_job_id VARCHAR(64) NULL COMMENT 'the Flink job id produced the last time this version was actually deployed, if any',
  change_summary VARCHAR(255) NULL COMMENT 'e.g. 创建/编辑保存/滚动升级/回滚至版本 N',
  rollback_of_version INT NULL COMMENT 'set when this row was itself produced by rolling back to an earlier version',
  created_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_job_version_snapshot (entity_type, entity_id, version_no)
);
