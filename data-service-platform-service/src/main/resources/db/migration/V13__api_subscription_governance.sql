CREATE TABLE data_service_api_subscription (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  app_id BIGINT NOT NULL,
  api_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/APPROVED/REJECTED/SUSPENDED',
  request_reason VARCHAR(500) NULL,
  qps_limit INT NOT NULL DEFAULT 20,
  daily_limit BIGINT NOT NULL DEFAULT 100000,
  valid_from DATETIME NULL,
  valid_until DATETIME NULL,
  ip_allowlist_json JSON NULL,
  requested_by VARCHAR(80) NOT NULL,
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_by VARCHAR(80) NULL,
  reviewed_at DATETIME NULL,
  review_comment VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_api_subscription (app_id, api_id),
  KEY idx_api_subscription_status (status, requested_at),
  KEY idx_api_subscription_api (api_id, status)
);

CREATE TABLE data_service_subscription_daily_usage (
  subscription_id BIGINT NOT NULL,
  usage_date DATE NOT NULL,
  request_count BIGINT NOT NULL DEFAULT 1,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (subscription_id, usage_date)
);

INSERT INTO data_service_api_subscription (
  app_id, api_id, status, request_reason, qps_limit, daily_limit,
  requested_by, requested_at, reviewed_by, reviewed_at
)
SELECT
  aa.app_id, aa.api_id, 'APPROVED', 'Migrated from legacy direct authorization',
  LEAST(a.qps_limit, 20), 100000,
  COALESCE(aa.granted_by, 'migration'), aa.created_at,
  COALESCE(aa.granted_by, 'migration'), aa.created_at
FROM data_service_app_api aa
JOIN data_service_app a ON a.id = aa.app_id;

INSERT INTO data_service_admin_permission (code, name) VALUES
  ('SUBSCRIPTION_READ', 'View API subscriptions'),
  ('SUBSCRIPTION_MANAGE', 'Request and manage API subscriptions'),
  ('SUBSCRIPTION_APPROVE', 'Approve API subscriptions');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('SUBSCRIPTION_READ', 'SUBSCRIPTION_MANAGE', 'SUBSCRIPTION_APPROVE')
WHERE role.code = 'SUPER_ADMIN';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('SUBSCRIPTION_READ', 'SUBSCRIPTION_MANAGE')
WHERE role.code = 'API_DEVELOPER';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('SUBSCRIPTION_READ', 'SUBSCRIPTION_APPROVE')
WHERE role.code = 'API_APPROVER';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code = 'SUBSCRIPTION_READ'
WHERE role.code = 'AUDITOR';
