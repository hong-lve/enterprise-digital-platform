-- SQL 流作业 now also supports 'connector'='redis' sinks (see
-- flink-connectors/redis-table-sink) - same shape as V11/V12/V19: optional,
-- comma-separated, user-declared since the platform can't parse the sink
-- table out of the SQL script reliably.

ALTER TABLE flink_sql_job ADD COLUMN redis_sink_tables VARCHAR(500) NULL COMMENT 'optional, comma-separated - Redis keys/tables this job writes to, for the lineage view';
