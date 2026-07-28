-- Kafka consumer group lag - the last missing piece of the observability
-- triangle across the pipeline (CDC message freshness on the source side,
-- this on the Kafka<->Flink boundary, backpressure on the Flink processing
-- side). Unlike those two, this is a real Kafka metric read via
-- AdminClient, not a proxy - Flink's KafkaSource commits offsets to Kafka
-- after each checkpoint when group.id is set (confirmed live via
-- kafka-consumer-groups.sh --describe against task-stats-job's group).
--
-- kafka_consumer_group_id/kafka_topics are nullable and set by whoever
-- submits the job (only they know what their jar actually consumes) - the
-- platform can't infer this from opaque programArgs without parsing
-- business-jar-specific CLI conventions, which would cross the "manages
-- lifecycle, doesn't understand business logic" boundary this repo keeps
-- elsewhere (see cdc_source's structured topic fields for the same reason).
-- Leaving them blank simply skips lag monitoring for that job.

ALTER TABLE flink_stream_job ADD COLUMN kafka_consumer_group_id VARCHAR(200) NULL COMMENT 'optional - the jars own Kafka consumer group.id, set by whoever submits the job';
ALTER TABLE flink_stream_job ADD COLUMN kafka_topics VARCHAR(500) NULL COMMENT 'optional, comma-separated - topics this job consumes, to scope lag calculation';
ALTER TABLE flink_stream_job ADD COLUMN consumer_lag_records BIGINT NULL COMMENT 'total records behind across kafka_topics latest offsets; null if kafka_consumer_group_id unset or no committed offsets yet';
ALTER TABLE flink_stream_job ADD COLUMN consumer_lag_alert_state VARCHAR(20) NOT NULL DEFAULT 'OK' COMMENT 'OK/ALERTING, independent of alert_state and backpressure_alert_state';
