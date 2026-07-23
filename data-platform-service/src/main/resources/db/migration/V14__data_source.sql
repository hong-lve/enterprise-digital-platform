-- 统一数据源注册表：把散落在各处的连接信息（CDC 源自己的 db_host/db_port/
-- db_username/db_password，application.yml 里写死的 ClickHouse/Doris 连接）
-- 收敛成一张可复用、可管理的数据源表，其它功能引用 id 即可，不用各自手填/
-- 手写连接信息。字段直接是 host/port/username/password，不是一个不透明的
-- jdbcUrl 字符串 - 跟 data-platform-service 的 sys_data_source 不同，
-- 这样 Debezium 的 database.hostname/database.port 等字段才能直接拆出来用，
-- 不需要反解析一个 URL 字符串。
--
-- flink_host/flink_port/flink_http_port 是"两个地址"问题的解法：
-- realtime-compute-service 自己的 JVM 跑在 Docker 外面，连 ClickHouse/Doris
-- 走宿主机映射端口（host/port）；但 SQL 流作业最终是 Flink 集群容器在执行
-- sink 的 INSERT，容器连这两个服务得走 Docker 网络里的服务名（flink_host/
-- flink_port，Doris 还需要单独的 flink_http_port，因为它的 sink 端口(FE
-- HTTP/Stream Load, 8030)跟查询端口(FE MySQL协议, 9030)不是同一个）。MySQL
-- 因为不在容器里、host.docker.internal 两边都能解析，暂时用不上这三个字段。
CREATE TABLE data_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  type VARCHAR(20) NOT NULL COMMENT 'MYSQL/CLICKHOUSE/DORIS/ORACLE',
  host VARCHAR(200) NOT NULL COMMENT 'realtime-compute-service 自己进程能连到的地址',
  port INT NOT NULL,
  username VARCHAR(100) NOT NULL,
  password VARCHAR(200) NOT NULL,
  database_name VARCHAR(160) NULL COMMENT '默认库，查询页仍可通过 /databases 接口现查现选',
  flink_host VARCHAR(200) NULL COMMENT 'Flink 集群容器可达地址，跟 host 不同才需要填，仅生成 SQL 流作业 sink 配置时用',
  flink_port INT NULL,
  flink_http_port INT NULL COMMENT '仅 Doris 需要：FE HTTP/Stream Load 端口，跟 port(FE MySQL协议查询端口)不是一个',
  status VARCHAR(20) NOT NULL DEFAULT 'UNTESTED' COMMENT 'UNTESTED/OK/FAILED - 上一次测试连接的结果，仅供展示',
  last_test_error TEXT NULL,
  environment VARCHAR(20) NOT NULL DEFAULT 'DEV' COMMENT 'DEV/STAGING/PROD - 逻辑标记，非物理隔离，见 V9__environment_field.sql',
  owner VARCHAR(100),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 把现在写死在 application.yml 里的 ClickHouse/Doris 连接迁移成真实数据行，
-- 保证 RealtimeQueryController 改成数据源驱动之后，实时数据查询页的既有
-- 功能不倒退。值跟 application.yml 当前默认值一一对应（这个仓库里没有设置
-- CLICKHOUSE_PASSWORD/DORIS_PASSWORD 环境变量覆盖它们，如果将来某个部署设了，
-- 需要手动在数据源管理页面里把这两行的密码更新一致）。
INSERT INTO data_source (name, type, host, port, username, password, database_name, flink_host, flink_port, flink_http_port, environment, owner) VALUES
('ClickHouse（实时分析库）', 'CLICKHOUSE', 'localhost', 8123, 'realtime', 'realtime123', 'realtime_analytics', 'clickhouse', 8123, NULL, 'DEV', 'system'),
('Doris（实时demo库）', 'DORIS', 'localhost', 9030, 'root', '', 'realtime_demo', 'doris', 9030, 8030, 'DEV', 'system');
