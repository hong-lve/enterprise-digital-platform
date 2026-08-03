CREATE TABLE data_service_api_rollout (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  api_id BIGINT NOT NULL,
  baseline_version_no INT NOT NULL,
  candidate_version_no INT NOT NULL,
  percentage INT NOT NULL DEFAULT 0,
  application_ids_json JSON NOT NULL,
  ip_rules_json JSON NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'ACTIVE/PROMOTED/ROLLED_BACK',
  note VARCHAR(500) NULL,
  started_by VARCHAR(80) NOT NULL,
  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(80) NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  finished_by VARCHAR(80) NULL,
  finished_at DATETIME NULL,
  active_api_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN status = 'ACTIVE' THEN api_id ELSE NULL END
  ) STORED,
  UNIQUE KEY uk_api_rollout_active (active_api_id),
  KEY idx_api_rollout_active (api_id, status),
  KEY idx_api_rollout_history (api_id, id)
);

ALTER TABLE data_service_call_log
  ADD COLUMN routed_version_no INT NULL AFTER api_id,
  ADD COLUMN rollout_id BIGINT NULL AFTER routed_version_no,
  ADD COLUMN rollout_variant VARCHAR(20) NULL AFTER rollout_id,
  ADD KEY idx_call_log_rollout (rollout_id, rollout_variant, occurred_at);

INSERT INTO data_service_admin_permission (code, name) VALUES
  ('CANARY_READ', 'View API canary rollouts'),
  ('CANARY_MANAGE', 'Manage API canary rollouts');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('CANARY_READ', 'CANARY_MANAGE')
WHERE role.code = 'SUPER_ADMIN';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code = 'CANARY_READ'
WHERE role.code IN ('API_DEVELOPER', 'AUDITOR');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('CANARY_READ', 'CANARY_MANAGE')
WHERE role.code = 'API_APPROVER';
