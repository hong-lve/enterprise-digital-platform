-- SQL 流作业 now supports 'connector'='jdbc' sinks pointed at Oracle too
-- (flink-connector-jdbc already ships an Oracle dialect out of the box -
-- only the driver jar needed mounting, see docker/bigdata/docker-compose.yml),
-- same shape as V11/V12: optional, comma-separated, user-declared since the
-- platform can't parse the sink table out of the SQL script reliably.

ALTER TABLE flink_sql_job ADD COLUMN oracle_sink_tables VARCHAR(500) NULL COMMENT 'optional, comma-separated - Oracle tables this job writes to, for the lineage view';
