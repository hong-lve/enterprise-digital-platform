CREATE TABLE data_service_change_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  action_type VARCHAR(80) NOT NULL,
  target_type VARCHAR(80) NOT NULL,
  target_id BIGINT NOT NULL,
  target_summary VARCHAR(300) NOT NULL,
  environment VARCHAR(20) NOT NULL,
  payload_json JSON NOT NULL,
  requester VARCHAR(80) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  approver VARCHAR(80) NULL,
  decision_comment VARCHAR(500) NULL,
  decided_at DATETIME NULL,
  active_flag TINYINT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_change_request_pending (action_type, target_type, target_id, active_flag),
  KEY idx_change_request_status (status, created_at)
);

INSERT INTO data_service_admin_permission (code, name) VALUES
  ('CHANGE_APPROVAL_READ', 'View production change requests'),
  ('CHANGE_APPROVAL_HANDLE', 'Approve or reject production changes');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('CHANGE_APPROVAL_READ', 'CHANGE_APPROVAL_HANDLE')
WHERE role.code IN ('SUPER_ADMIN', 'API_APPROVER');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code = 'CHANGE_APPROVAL_READ'
WHERE role.code IN ('API_DEVELOPER', 'AUDITOR');
