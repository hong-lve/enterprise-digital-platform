CREATE TABLE data_service_app_secret (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  app_id BIGINT NOT NULL,
  secret_version INT NOT NULL,
  secret_hash VARCHAR(200) NOT NULL,
  secret_ciphertext TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/GRACE/REVOKED',
  expires_at DATETIME NULL,
  last_used_at DATETIME NULL,
  created_by VARCHAR(80) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_by VARCHAR(80) NULL,
  revoked_at DATETIME NULL,
  UNIQUE KEY uk_app_secret_version (app_id, secret_version),
  KEY idx_app_secret_runtime (app_id, status, expires_at)
);

INSERT INTO data_service_app_secret (
  app_id, secret_version, secret_hash, secret_ciphertext,
  status, created_by, created_at
)
SELECT
  id, secret_version, app_secret_hash, app_secret_ciphertext,
  'ACTIVE', 'migration', COALESCE(last_rotated_at, created_at)
FROM data_service_app
WHERE app_secret_ciphertext IS NOT NULL;
