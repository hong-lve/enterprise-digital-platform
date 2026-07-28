CREATE TABLE data_service_dataset (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  source_type VARCHAR(40) NOT NULL COMMENT 'DORIS/CLICKHOUSE/MYSQL/REDIS/etc.',
  source_name VARCHAR(160) NOT NULL,
  table_name VARCHAR(200) NOT NULL,
  owner VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE data_service_api (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dataset_id BIGINT NOT NULL,
  name VARCHAR(160) NOT NULL,
  path VARCHAR(240) NOT NULL,
  method VARCHAR(10) NOT NULL DEFAULT 'GET',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  cache_ttl_seconds INT NULL,
  max_page_size INT NOT NULL DEFAULT 500,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_api_path_method (path, method),
  KEY idx_data_service_api_dataset_id (dataset_id)
);

CREATE TABLE data_service_app (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  app_key VARCHAR(80) NOT NULL,
  app_secret_hash VARCHAR(200) NOT NULL,
  name VARCHAR(160) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
  qps_limit INT NOT NULL DEFAULT 50,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_app_key (app_key)
);

CREATE TABLE data_service_app_api (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  app_id BIGINT NOT NULL,
  api_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_app_api (app_id, api_id)
);

CREATE TABLE data_service_call_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  app_key VARCHAR(80) NULL,
  api_path VARCHAR(240) NOT NULL,
  method VARCHAR(10) NOT NULL,
  status_code INT NOT NULL,
  elapsed_ms BIGINT NOT NULL,
  client_ip VARCHAR(80) NULL,
  error_message VARCHAR(500) NULL,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_data_service_call_log_time (occurred_at),
  KEY idx_data_service_call_log_app_key (app_key)
);
