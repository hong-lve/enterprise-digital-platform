CREATE TABLE data_service_contract_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  api_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  baseline_version_no INT NULL,
  severity VARCHAR(20) NOT NULL COMMENT 'COMPATIBLE/RISKY/BREAKING',
  findings_json JSON NOT NULL,
  generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_contract_report_version (api_id, version_no)
);

CREATE TABLE data_service_contract_test_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  api_id BIGINT NOT NULL,
  name VARCHAR(160) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  parameters_json JSON NOT NULL,
  page_no INT NOT NULL DEFAULT 1,
  page_size INT NOT NULL DEFAULT 20,
  assertions_json JSON NOT NULL,
  created_by VARCHAR(80) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_contract_case_api (api_id, enabled)
);

CREATE TABLE data_service_contract_test_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  case_id BIGINT NOT NULL,
  api_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  status VARCHAR(20) NOT NULL COMMENT 'PASSED/FAILED/ERROR',
  elapsed_ms BIGINT NULL,
  row_count INT NULL,
  failure_message VARCHAR(1000) NULL,
  run_by VARCHAR(80) NOT NULL,
  run_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_contract_run_api (api_id, version_no, run_at),
  KEY idx_contract_run_case (case_id, run_at)
);

INSERT INTO data_service_admin_permission (code, name) VALUES
  ('CONTRACT_TEST_READ', 'View API contract tests'),
  ('CONTRACT_TEST_MANAGE', 'Manage and execute API contract tests');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('CONTRACT_TEST_READ', 'CONTRACT_TEST_MANAGE')
WHERE role.code = 'SUPER_ADMIN';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('CONTRACT_TEST_READ', 'CONTRACT_TEST_MANAGE')
WHERE role.code = 'API_DEVELOPER';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code = 'CONTRACT_TEST_READ'
WHERE role.code IN ('API_APPROVER', 'AUDITOR');
