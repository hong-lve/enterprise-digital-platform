CREATE TABLE data_service_connection (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  engine_type VARCHAR(40) NOT NULL,
  host VARCHAR(255) NOT NULL,
  port INT NOT NULL,
  database_name VARCHAR(160) NOT NULL,
  username VARCHAR(160) NOT NULL,
  password_ciphertext TEXT NOT NULL,
  pool_min_idle INT NOT NULL DEFAULT 0,
  pool_max_size INT NOT NULL DEFAULT 10,
  connection_timeout_ms BIGINT NOT NULL DEFAULT 10000,
  query_timeout_seconds INT NOT NULL DEFAULT 10,
  environment VARCHAR(20) NOT NULL DEFAULT 'DEV',
  owner VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  last_test_status VARCHAR(20) NULL,
  last_test_message VARCHAR(500) NULL,
  last_test_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_connection_name (name),
  KEY idx_data_service_connection_status (status),
  KEY idx_data_service_connection_engine (engine_type)
);

ALTER TABLE data_service_dataset
  ADD COLUMN connection_id BIGINT NULL AFTER connection_mode,
  ADD KEY idx_data_service_dataset_connection_id (connection_id);
