CREATE TABLE flink_cluster (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  environment VARCHAR(20) NOT NULL,
  deployment_mode VARCHAR(30) NOT NULL DEFAULT 'STANDALONE' COMMENT 'STANDALONE/KUBERNETES_OPERATOR',
  rest_url VARCHAR(500) NULL,
  sql_gateway_url VARCHAR(500) NULL,
  kube_api_url VARCHAR(500) NULL,
  kube_namespace VARCHAR(120) NULL,
  kube_token_env VARCHAR(120) NULL,
  flink_image VARCHAR(500) NULL,
  service_account VARCHAR(120) NULL,
  default_for_environment TINYINT(1) NOT NULL DEFAULT 0,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  owner VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_flink_cluster_name (name),
  INDEX idx_flink_cluster_environment (environment, enabled, default_for_environment)
);

ALTER TABLE flink_stream_job ADD COLUMN cluster_id BIGINT NULL AFTER environment;
ALTER TABLE flink_sql_job ADD COLUMN cluster_id BIGINT NULL AFTER environment;

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible)
VALUES (122, 40, 'Flink 集群', '/realtime/flink-clusters', 'FlinkClustersPage', 'ClusterOutlined', 'realtime:flink-cluster:view', 'MENU', 54, 'Y'),
       (123, 122, 'Flink 集群-管理', NULL, NULL, NULL, 'realtime:flink-cluster:manage', 'BUTTON', 1, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 122), (1, 123);
