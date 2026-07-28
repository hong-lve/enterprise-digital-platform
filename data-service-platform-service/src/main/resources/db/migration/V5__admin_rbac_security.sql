CREATE TABLE data_service_admin_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(80) NOT NULL,
  password_hash VARCHAR(200) NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME NULL,
  last_login_at DATETIME NULL,
  password_changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_admin_username (username)
);

CREATE TABLE data_service_admin_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_admin_role_code (code)
);

CREATE TABLE data_service_admin_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(100) NOT NULL,
  name VARCHAR(120) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_admin_permission_code (code)
);

CREATE TABLE data_service_admin_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, role_id)
);

CREATE TABLE data_service_admin_role_permission (
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE data_service_admin_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  client_ip VARCHAR(80) NULL,
  user_agent VARCHAR(500) NULL,
  revoked_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_admin_session_token (token_hash),
  KEY idx_data_service_admin_session_user (user_id),
  KEY idx_data_service_admin_session_expiry (expires_at)
);

CREATE TABLE data_service_admin_login_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NULL,
  username VARCHAR(80) NOT NULL,
  success TINYINT(1) NOT NULL,
  failure_reason VARCHAR(200) NULL,
  client_ip VARCHAR(80) NULL,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_data_service_admin_login_time (occurred_at),
  KEY idx_data_service_admin_login_username (username)
);

INSERT INTO data_service_admin_role (code, name, description) VALUES
  ('SUPER_ADMIN', '超级管理员', '拥有数据服务平台全部管理权限'),
  ('API_DEVELOPER', 'API 开发者', '管理数据源、数据集和数据 API'),
  ('AUDITOR', '审计员', '只读查看 API、应用和调用审计');

INSERT INTO data_service_admin_permission (code, name) VALUES
  ('DATASOURCE_READ', '查看数据源'),
  ('DATASOURCE_MANAGE', '管理数据源'),
  ('DATASET_READ', '查看数据集'),
  ('DATASET_MANAGE', '管理数据集'),
  ('API_READ', '查看数据 API'),
  ('API_MANAGE', '管理数据 API'),
  ('APPLICATION_READ', '查看调用应用'),
  ('APPLICATION_MANAGE', '管理调用应用'),
  ('AUDIT_READ', '查看调用审计');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
CROSS JOIN data_service_admin_permission permission
WHERE role.code = 'SUPER_ADMIN';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN (
    'DATASOURCE_READ', 'DATASOURCE_MANAGE', 'DATASET_READ', 'DATASET_MANAGE',
    'API_READ', 'API_MANAGE', 'APPLICATION_READ', 'AUDIT_READ'
  )
WHERE role.code = 'API_DEVELOPER';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('DATASOURCE_READ', 'DATASET_READ', 'API_READ', 'APPLICATION_READ', 'AUDIT_READ')
WHERE role.code = 'AUDITOR';
