-- Every alert transition (ALERTING/OK) so far only ever fired a one-off
-- message/webhook and was gone - nothing let anyone see past alerts for an
-- entity or compute how often something has been flapping. This persists
-- every notifyFailure/notifyRecovery call as its own row, independent of the
-- entity's own alert_state columns (which only ever hold the *current*
-- state).

CREATE TABLE alert_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type VARCHAR(20) NOT NULL COMMENT 'CDC_SOURCE/FLINK_JOB/SQL_JOB',
  entity_id BIGINT NOT NULL,
  entity_name VARCHAR(200) NOT NULL,
  dimension VARCHAR(30) NOT NULL COMMENT 'CONNECTOR_FAILURE/MESSAGE_LAG/JOB_FAILURE/BACKPRESSURE/CONSUMER_LAG',
  state VARCHAR(10) NOT NULL COMMENT 'ALERTING/OK',
  message VARCHAR(1000),
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_alert_history_entity (entity_type, entity_id),
  INDEX idx_alert_history_occurred_at (occurred_at)
);
