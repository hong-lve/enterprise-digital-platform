-- CDC connector status alone can't catch a connector that Kafka Connect
-- still reports RUNNING but has silently stopped producing new events -
-- lag_seconds/lag_alert_state track "how stale is the latest message we've
-- seen" as a separate failure dimension from alert_state (connector
-- FAILED/RUNNING), since a source can be RUNNING but stale, or RUNNING and
-- healthy - collapsing them into one field would let one mask the other.

ALTER TABLE cdc_source ADD COLUMN lag_seconds BIGINT NULL COMMENT 'seconds since the most recent CDC message across this source''s topics; null if never received one';
ALTER TABLE cdc_source ADD COLUMN lag_alert_state VARCHAR(20) NOT NULL DEFAULT 'OK' COMMENT 'OK/ALERTING, independent of alert_state';
