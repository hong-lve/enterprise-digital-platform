-- A streaming job can stay RUNNING while falling behind its upstream Kafka
-- source (backpressure) - status checks alone can't catch that.
-- backpressure_ratio/backpressure_alert_state track this as a separate
-- failure dimension from alert_state (job FAILED/RUNNING), since a job can
-- be RUNNING but backpressured, or RUNNING and healthy - collapsing them
-- into one field would let one mask the other. Same pattern as
-- cdc_source.lag_seconds/lag_alert_state (V5).

ALTER TABLE flink_stream_job ADD COLUMN backpressure_ratio DOUBLE NULL COMMENT 'fraction of the last poll interval spent backpressured (0.0-1.0); null until two samples exist';
ALTER TABLE flink_stream_job ADD COLUMN backpressure_alert_state VARCHAR(20) NOT NULL DEFAULT 'OK' COMMENT 'OK/ALERTING, independent of alert_state';
