-- Replaces the cross-database platform_auth.sys_user/sys_role/sys_menu/...
-- tables (owned by enterprise-portal-service, which no longer exists in
-- this checkout) with local equivalents. This app went back to local
-- username/password login (no more OIDC/Keycloak, no more cross-app SSO -
-- data-platform is the only app left here), so there's no other service to
-- share an auth schema with anymore.
--
-- Seed data below is carried over as-is from the live platform_auth data at
-- the time enterprise-portal-service was removed: the ADMIN role, the
-- admin user (same bcrypt hash, still logs in with admin123), and the 12
-- menu rows actually used by this app's sidebar (AppShell.tsx's
-- allowedPaths only covers the /realtime tree - the old shared portal/
-- ops-admin/screen-admin menu rows aren't relevant here and weren't
-- carried over).
CREATE TABLE sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(80) NOT NULL UNIQUE,
  display_name VARCHAR(100) NOT NULL,
  email VARCHAR(150) NULL,
  password_hash VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE sys_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_key VARCHAR(80) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(255),
  status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
  data_scope VARCHAR(40) NOT NULL DEFAULT 'ALL',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE sys_menu (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  parent_id BIGINT NOT NULL DEFAULT 0,
  title VARCHAR(100) NOT NULL,
  path VARCHAR(255) NULL,
  component VARCHAR(150) NULL,
  icon VARCHAR(100) NULL,
  permission VARCHAR(150) NULL,
  type VARCHAR(20) NOT NULL DEFAULT 'MENU',
  sort_order INT NOT NULL DEFAULT 0,
  visible VARCHAR(1) NOT NULL DEFAULT 'Y',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_role_menu (
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, menu_id)
);

CREATE TABLE sys_login_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(80) NOT NULL,
  ip_address VARCHAR(64) NULL,
  user_agent VARCHAR(255) NULL,
  result VARCHAR(20) NOT NULL,
  message VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Also cross-database (platform_auth.sys_message/sys_message_receiver)
-- before enterprise-portal-service was removed - RealtimeAlertService/
-- WebhookAlertSender write here for in-app alert notifications. No seed
-- data needed, these start empty and get populated at runtime.
CREATE TABLE sys_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(160) NOT NULL,
  content TEXT,
  type VARCHAR(40) NOT NULL DEFAULT 'NOTICE',
  link_url VARCHAR(255),
  sender VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_message_receiver (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id BIGINT NOT NULL,
  receiver VARCHAR(100) NOT NULL,
  read_status VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
  read_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO sys_role (id, role_key, name, description, status, data_scope) VALUES
(1, 'ADMIN', '系统管理员', '拥有系统全部权限', 'ENABLED', 'ALL');

INSERT INTO sys_user (id, username, display_name, email, password_hash, status) VALUES
(1, 'admin', '平台管理员', 'admin@example.com', '$2a$10$eGrc.68zNEpIlWgcfZxTwOOop/TCMr2H10B8m6BYOyKvOmG/.HXjW', 'ENABLED');

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(40, 0, '实时计算', '/realtime', 'Layout', 'ThunderboltOutlined', 'realtime:view', 'MENU', 40, 'Y'),
(41, 40, '实时计算总览', '/realtime/overview', 'RealtimeOverviewPage', 'DashboardOutlined', 'realtime:overview:view', 'MENU', 41, 'Y'),
(50, 40, '数据源配置', '/realtime/data-sources', 'DataSourcesPage', 'ClusterOutlined', 'realtime:datasource:view', 'MENU', 42, 'Y'),
(42, 40, 'CDC 数据源', '/realtime/cdc-sources', 'CdcSourcesPage', 'DatabaseOutlined', 'realtime:cdc:view', 'MENU', 43, 'Y'),
(45, 40, 'Flink SQL 查询', '/realtime/flink-sql', 'FlinkSqlPage', 'CodeOutlined', 'realtime:flink-sql:execute', 'MENU', 44, 'Y'),
(48, 40, 'SQL 流作业', '/realtime/sql-jobs', 'FlinkSqlJobsPage', 'FunctionOutlined', 'realtime:sql-job:view', 'MENU', 45, 'Y'),
(51, 40, 'JAR 包管理', '/realtime/jars', 'JarPackagesPage', 'FileZipOutlined', 'realtime:jar:view', 'MENU', 46, 'Y'),
(43, 40, 'Flink 流作业', '/realtime/flink-jobs', 'FlinkStreamJobsPage', 'ClusterOutlined', 'realtime:flink:view', 'MENU', 47, 'Y'),
(44, 40, '实时数据查询', '/realtime/query', 'RealtimeQueryPage', 'FileSearchOutlined', 'realtime:query:execute', 'MENU', 48, 'Y'),
(46, 40, '实时血缘', '/realtime/lineage', 'LineagePage', 'NodeIndexOutlined', 'realtime:lineage:view', 'MENU', 49, 'Y'),
(49, 40, '告警历史', '/realtime/alert-history', 'AlertHistoryPage', 'FileSearchOutlined', 'realtime:alert-history:view', 'MENU', 50, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;
