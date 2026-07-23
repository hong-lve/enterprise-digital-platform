-- SQL 流作业 now supports 'connector'='doris' sinks too (see
-- flink-doris-connector mounted in docker/bigdata/docker-compose.yml),
-- alongside the existing ClickHouse jdbc sink from
-- V11__flink_sql_job_clickhouse_sink.sql - same reasoning, same shape:
-- optional, comma-separated, user-declared since the platform can't parse
-- the sink table out of the SQL script reliably.

ALTER TABLE flink_sql_job ADD COLUMN doris_sink_tables VARCHAR(500) NULL COMMENT 'optional, comma-separated - Doris tables this job writes to, for the lineage view';
