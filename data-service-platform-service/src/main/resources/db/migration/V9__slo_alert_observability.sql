ALTER TABLE data_service_call_log
  ADD COLUMN trace_id VARCHAR(64) NULL AFTER request_id,
  ADD KEY idx_data_service_call_log_trace_id (trace_id);

CREATE TABLE data_service_slo_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  api_id BIGINT NOT NULL,
  name VARCHAR(160) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  window_minutes INT NOT NULL DEFAULT 5,
  min_requests INT NOT NULL DEFAULT 10,
  min_success_rate DECIMAL(6,3) NOT NULL DEFAULT 99.000,
  max_p95_ms BIGINT NOT NULL DEFAULT 1000,
  created_by VARCHAR(80) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_slo_rule_api (api_id)
);

CREATE TABLE data_service_alert_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id BIGINT NOT NULL,
  api_id BIGINT NOT NULL,
  alert_type VARCHAR(40) NOT NULL COMMENT 'SUCCESS_RATE/LATENCY_P95',
  status VARCHAR(30) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACKNOWLEDGED/RESOLVED',
  observed_value DECIMAL(16,3) NOT NULL,
  threshold_value DECIMAL(16,3) NOT NULL,
  sample_count INT NOT NULL,
  message VARCHAR(500) NOT NULL,
  acknowledged_by VARCHAR(80) NULL,
  acknowledged_at DATETIME NULL,
  resolved_at DATETIME NULL,
  opened_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_data_service_alert_status (status, opened_at),
  KEY idx_data_service_alert_api (api_id, opened_at),
  KEY idx_data_service_alert_rule_type (rule_id, alert_type)
);
