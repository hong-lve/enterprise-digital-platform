-- JAR 包管理: 让 Flink 流作业页面选 jar 而不是手输本地绝对路径。Mirrors
-- ops-admin-service's sys_file (local uploads/ dir + UUID-renamed stored
-- file), not shared with it - this table lives in realtime_compute_db and
-- is scoped to Flink job jars specifically. No environment column: a jar is
-- a shared binary artifact, not itself environment-scoped - only the job
-- that references its storage_path is.
CREATE TABLE flink_jar (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  original_name VARCHAR(300) NOT NULL,
  stored_name VARCHAR(300) NOT NULL,
  storage_path VARCHAR(500) NOT NULL COMMENT '绝对路径，直接可以填进 FlinkStreamJobEntity.jarPath',
  size_bytes BIGINT NOT NULL,
  description VARCHAR(300) NULL,
  uploader VARCHAR(100),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
