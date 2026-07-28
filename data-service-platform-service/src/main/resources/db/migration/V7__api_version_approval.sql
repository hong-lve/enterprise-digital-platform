ALTER TABLE data_service_api
  ADD COLUMN published_version_id BIGINT NULL AFTER version;

CREATE TABLE data_service_api_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  api_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  dataset_id BIGINT NOT NULL,
  name VARCHAR(160) NOT NULL,
  description VARCHAR(500) NULL,
  path VARCHAR(240) NOT NULL,
  method VARCHAR(10) NOT NULL,
  query_sql TEXT NOT NULL,
  parameters_json JSON NULL,
  cache_ttl_seconds INT NULL,
  max_page_size INT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
    COMMENT 'DRAFT/PENDING_APPROVAL/REJECTED/PUBLISHED/ARCHIVED',
  change_summary VARCHAR(500) NULL,
  created_by VARCHAR(80) NOT NULL,
  submitted_by VARCHAR(80) NULL,
  submitted_at DATETIME NULL,
  reviewed_by VARCHAR(80) NULL,
  reviewed_at DATETIME NULL,
  review_comment VARCHAR(500) NULL,
  published_at DATETIME NULL,
  source_version_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_api_version (api_id, version_no),
  KEY idx_data_service_api_version_status (status),
  KEY idx_data_service_api_version_api (api_id, id)
);

INSERT INTO data_service_api_version (
  api_id, version_no, dataset_id, name, description, path, method, query_sql,
  parameters_json, cache_ttl_seconds, max_page_size, status, change_summary,
  created_by, submitted_by, submitted_at, reviewed_by, reviewed_at, published_at, created_at
)
SELECT
  id, version, dataset_id, name, description, path, method, query_sql,
  parameters_json, cache_ttl_seconds, max_page_size,
  CASE WHEN status = 'PUBLISHED' THEN 'PUBLISHED' ELSE 'DRAFT' END,
  '历史定义初始化', 'migration',
  CASE WHEN status = 'PUBLISHED' THEN 'migration' ELSE NULL END,
  CASE WHEN status = 'PUBLISHED' THEN COALESCE(published_at, updated_at) ELSE NULL END,
  CASE WHEN status = 'PUBLISHED' THEN 'migration' ELSE NULL END,
  CASE WHEN status = 'PUBLISHED' THEN COALESCE(published_at, updated_at) ELSE NULL END,
  CASE WHEN status = 'PUBLISHED' THEN COALESCE(published_at, updated_at) ELSE NULL END,
  created_at
FROM data_service_api;

UPDATE data_service_api api
JOIN data_service_api_version version
  ON version.api_id = api.id
 AND version.version_no = api.version
 AND version.status = 'PUBLISHED'
SET api.published_version_id = version.id;

INSERT INTO data_service_admin_permission (code, name) VALUES
  ('API_APPROVE', '审批、下线与回滚数据 API');

INSERT INTO data_service_admin_role (code, name, description) VALUES
  ('API_APPROVER', 'API 审批人', '审批发布、下线和回滚数据 API');

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code = 'API_APPROVE'
WHERE role.code = 'SUPER_ADMIN';

INSERT INTO data_service_admin_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM data_service_admin_role role
JOIN data_service_admin_permission permission
  ON permission.code IN ('API_READ', 'API_APPROVE', 'DATASET_READ', 'DATASOURCE_READ', 'AUDIT_READ')
WHERE role.code = 'API_APPROVER';
