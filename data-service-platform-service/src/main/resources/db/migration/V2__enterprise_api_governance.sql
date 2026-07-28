ALTER TABLE data_service_dataset
  ADD COLUMN description VARCHAR(500) NULL AFTER name,
  ADD COLUMN connection_mode VARCHAR(40) NOT NULL DEFAULT 'PLATFORM' AFTER source_name;

ALTER TABLE data_service_api
  ADD COLUMN description VARCHAR(500) NULL AFTER name,
  ADD COLUMN query_sql TEXT NULL AFTER method,
  ADD COLUMN parameters_json JSON NULL AFTER query_sql,
  ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER status,
  ADD COLUMN published_at DATETIME NULL AFTER max_page_size;

ALTER TABLE data_service_call_log
  ADD COLUMN api_id BIGINT NULL AFTER id,
  ADD COLUMN request_id VARCHAR(64) NULL AFTER api_id,
  ADD COLUMN row_count INT NULL AFTER elapsed_ms,
  ADD COLUMN test_call TINYINT(1) NOT NULL DEFAULT 0 AFTER row_count,
  ADD KEY idx_data_service_call_log_api_id (api_id),
  ADD KEY idx_data_service_call_log_request_id (request_id);
