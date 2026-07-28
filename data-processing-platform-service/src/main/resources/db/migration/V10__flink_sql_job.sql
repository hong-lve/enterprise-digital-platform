-- SQL 流作业：让用户在页面里写 Flink SQL（若干 CREATE TABLE + 一条 INSERT
-- INTO）定义处理逻辑，不用写 Java 打 jar。v1 范围只做 Kafka -> Kafka（写
-- ClickHouse 依然走 flink_stream_job 的 jar 方式，flink-connector-jdbc 不
-- 支持 ClickHouse 方言，见这次的调研结论）。
--
-- 没有 restart_strategy/restart_attempts/restart_delay_seconds 字段：实测
-- 确认 Flink SQL Gateway 的 session properties（POST /v1/sessions 的
-- properties map）里 execution.checkpointing.interval/parallelism.default
-- 会真正生效，但 restart-strategy/restart-strategy.type 都不会——提交出来
-- 的作业实际用的还是"Cluster level default restart strategy"，跟
-- session 传了什么无关。与其在表单里放一个不生效的假选项，不如干脆不暴露，
-- 重启行为交给 Flink 集群自己的默认策略。

CREATE TABLE flink_sql_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  sql_script TEXT NOT NULL COMMENT '若干 CREATE TABLE + 恰好一条 INSERT INTO，最后一条必须是 INSERT INTO',
  parallelism INT NOT NULL DEFAULT 1,
  checkpoint_interval_ms INT NOT NULL DEFAULT 10000,
  flink_job_id VARCHAR(64) NULL,
  savepoint_path VARCHAR(500) NULL COMMENT 'saved on stop; next start resumes from here if present',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/FAILED/CANCELED/FINISHED',
  last_error TEXT NULL,
  alert_state VARCHAR(20) NULL,
  backpressure_ratio DOUBLE NULL,
  backpressure_alert_state VARCHAR(20) NULL,
  -- Optional - only the person submitting the job knows what its SQL script
  -- actually consumes; blank skips consumer-lag monitoring entirely. Same
  -- reasoning as flink_stream_job's kafka_consumer_group_id/kafka_topics -
  -- parsing this out of the SQL text would be fragile.
  kafka_consumer_group_id VARCHAR(200) NULL,
  kafka_topics VARCHAR(500) NULL,
  consumer_lag_records BIGINT NULL,
  consumer_lag_alert_state VARCHAR(20) NULL,
  environment VARCHAR(20) NOT NULL DEFAULT 'DEV' COMMENT 'DEV/STAGING/PROD - 逻辑标记，非物理隔离',
  owner VARCHAR(100),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
