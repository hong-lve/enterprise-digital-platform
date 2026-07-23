-- 阶段6 环境隔离：逻辑标记，非物理集群隔离——本地 docker/bigdata 环境只有
-- 一套 Flink/Kafka/ClickHouse 实例，没有真正的 dev/staging/prod 分离集群
-- (见 docker/bigdata/README.md)。这里加的 environment 列只用于 (1) 前端展示
-- 标签 (2) 对 PROD 标记的资源做额外的操作权限门禁 (EnvironmentGuard)，
-- 不会把流量路由到不同的 Flink/Kafka/ClickHouse 端点。

ALTER TABLE cdc_source ADD COLUMN environment VARCHAR(20) NOT NULL DEFAULT 'DEV' COMMENT 'DEV/STAGING/PROD - 逻辑标记，非物理隔离';
ALTER TABLE flink_stream_job ADD COLUMN environment VARCHAR(20) NOT NULL DEFAULT 'DEV' COMMENT 'DEV/STAGING/PROD - 逻辑标记，非物理隔离';
