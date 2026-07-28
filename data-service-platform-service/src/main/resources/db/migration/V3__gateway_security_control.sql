ALTER TABLE data_service_app
  ADD COLUMN description VARCHAR(500) NULL AFTER name,
  ADD COLUMN app_secret_ciphertext TEXT NULL AFTER app_secret_hash,
  ADD COLUMN secret_version INT NOT NULL DEFAULT 1 AFTER app_secret_ciphertext,
  ADD COLUMN last_rotated_at DATETIME NULL AFTER qps_limit;

ALTER TABLE data_service_app_api
  ADD COLUMN granted_by VARCHAR(100) NULL AFTER api_id;

CREATE TABLE data_service_request_nonce (
  app_key VARCHAR(80) NOT NULL,
  nonce VARCHAR(120) NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (app_key, nonce),
  KEY idx_data_service_nonce_expires_at (expires_at)
);

CREATE TABLE data_service_rate_limit_counter (
  app_key VARCHAR(80) NOT NULL,
  window_second BIGINT NOT NULL,
  request_count INT NOT NULL DEFAULT 1,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (app_key, window_second),
  KEY idx_data_service_rate_limit_window (window_second)
);
