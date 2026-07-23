-- Last missing link for the real-time-only lineage view (MySQL table via
-- CDC source -> Kafka topic -> Flink job -> ClickHouse table): which
-- ClickHouse table(s) a job writes to isn't something the platform can
-- infer from the jar (same reasoning as kafka_consumer_group_id/
-- kafka_topics in V7 - only the person submitting the job knows). Optional
-- and comma-separated for the same reason those are: not every job writes
-- to ClickHouse, and a job can write to more than one table.

ALTER TABLE flink_stream_job ADD COLUMN clickhouse_sink_tables VARCHAR(500) NULL COMMENT 'optional, comma-separated - ClickHouse tables this job writes to, for the lineage view';
