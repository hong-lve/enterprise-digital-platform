ALTER TABLE data_service_alert_event
  ADD COLUMN active_flag TINYINT NULL AFTER status;

UPDATE data_service_alert_event
SET active_flag = 1
WHERE status IN ('OPEN', 'ACKNOWLEDGED');

ALTER TABLE data_service_alert_event
  ADD UNIQUE KEY uk_data_service_active_alert (rule_id, alert_type, active_flag);

ALTER TABLE data_service_alert_event
  MODIFY COLUMN active_flag TINYINT NULL DEFAULT 1;
