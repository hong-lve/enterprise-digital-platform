CREATE TABLE data_service_operation_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor VARCHAR(80) NULL,
  client_ip VARCHAR(80) NULL,
  trace_id VARCHAR(64) NULL,
  http_method VARCHAR(10) NOT NULL,
  request_path VARCHAR(300) NOT NULL,
  operation VARCHAR(160) NULL,
  resource_id VARCHAR(80) NULL,
  status VARCHAR(20) NOT NULL COMMENT 'SUCCESS/FAILURE',
  status_code INT NOT NULL,
  error_message VARCHAR(500) NULL,
  previous_hash CHAR(64) NULL,
  record_hash CHAR(64) NOT NULL,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_operation_audit_time (occurred_at),
  KEY idx_operation_audit_actor (actor, occurred_at),
  KEY idx_operation_audit_trace (trace_id)
);

CREATE TABLE data_service_audit_chain_head (
  id TINYINT PRIMARY KEY,
  last_hash CHAR(64) NULL
);

INSERT INTO data_service_audit_chain_head (id, last_hash) VALUES (1, NULL);

CREATE TABLE data_service_notification_channel (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  channel_type VARCHAR(30) NOT NULL COMMENT 'WEBHOOK/DINGTALK/WECHAT',
  endpoint_ciphertext TEXT NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_by VARCHAR(80) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_notification_channel_name (name)
);

CREATE TABLE data_service_notification_delivery (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  channel_id BIGINT NOT NULL,
  alert_event_id BIGINT NULL,
  event_type VARCHAR(30) NOT NULL COMMENT 'ALERT_OPENED/ALERT_RESOLVED',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RETRY/SENT/DEAD',
  payload_json JSON NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(500) NULL,
  sent_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_notification_delivery_due (status, next_attempt_at),
  KEY idx_notification_delivery_alert (alert_event_id)
);

INSERT INTO data_service_admin_permission (code, name) VALUES
  ('GOVERNANCE_READ', '查看通知与操作审计'),
  ('GOVERNANCE_MANAGE', '管理通知渠道与治理配置');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('GOVERNANCE_READ', 'GOVERNANCE_MANAGE')
WHERE role.code = 'SUPER_ADMIN';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code = 'GOVERNANCE_READ'
WHERE role.code IN ('API_DEVELOPER', 'API_APPROVER', 'AUDITOR');
