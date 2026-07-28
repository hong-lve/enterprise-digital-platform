-- First local business table for data-processing-platform-service: a saved CDC
-- source config (which MySQL instance/tables to capture, and the resulting
-- Kafka topic prefix), plus the deployed Debezium connector's name/status
-- once started via Kafka Connect.

CREATE TABLE cdc_source (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(160) NOT NULL,
  db_host VARCHAR(200) NOT NULL,
  db_port INT NOT NULL DEFAULT 3306,
  db_username VARCHAR(100) NOT NULL,
  db_password VARCHAR(200) NOT NULL,
  database_name VARCHAR(160) NOT NULL,
  table_include_list VARCHAR(500) NOT NULL COMMENT 'comma-separated db.table list, Debezium table.include.list format',
  topic_prefix VARCHAR(100) NOT NULL,
  connector_name VARCHAR(150) NULL COMMENT 'connector name once deployed to Kafka Connect',
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/RUNNING/PAUSED/FAILED',
  last_error TEXT NULL,
  owner VARCHAR(100),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
