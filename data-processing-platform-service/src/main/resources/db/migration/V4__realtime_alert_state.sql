-- Flink jobs never reach a terminal "done" state on their own, and CDC
-- connectors run indefinitely too - failures on either only get noticed if
-- someone happens to look at the Flink/Kafka Connect UI. alert_state tracks
-- an OK/ALERTING edge (same idea as data_task.alert_state /
-- data_quality_rule.alert_state in data-processing-platform-service) so a message only
-- fires on the transition, not on every poll that still sees a failure.

ALTER TABLE flink_stream_job ADD COLUMN alert_state VARCHAR(20) NOT NULL DEFAULT 'OK' COMMENT 'OK/ALERTING';
ALTER TABLE cdc_source ADD COLUMN alert_state VARCHAR(20) NOT NULL DEFAULT 'OK' COMMENT 'OK/ALERTING';
