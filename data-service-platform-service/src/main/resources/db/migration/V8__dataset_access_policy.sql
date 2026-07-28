CREATE TABLE data_service_dataset_policy (
  dataset_id BIGINT PRIMARY KEY,
  row_filter_sql VARCHAR(1000) NULL,
  updated_by VARCHAR(80) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE data_service_dataset_column_policy (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  dataset_id BIGINT NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  action VARCHAR(20) NOT NULL COMMENT 'MASK/HIDE',
  mask_type VARCHAR(20) NULL COMMENT 'FULL/PARTIAL/EMAIL/PHONE/HASH',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dataset_column_policy (dataset_id, column_name),
  KEY idx_dataset_column_policy_dataset (dataset_id)
);
