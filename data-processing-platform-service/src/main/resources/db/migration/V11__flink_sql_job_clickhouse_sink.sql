-- SQL 流作业 now supports 'connector'='jdbc' sinks pointed at ClickHouse
-- (see flink-connectors/clickhouse-jdbc-dialect), not just Kafka - this
-- mirrors V8__flink_clickhouse_sink_tables.sql's reasoning exactly: which
-- ClickHouse table(s) a job writes to isn't something the platform can
-- parse out of the SQL script reliably, so the submitter declares it, same
-- as jar jobs already do. Optional and comma-separated for the same
-- reason: not every SQL job writes to ClickHouse, and a job can write to
-- more than one table.

ALTER TABLE flink_sql_job ADD COLUMN clickhouse_sink_tables VARCHAR(500) NULL COMMENT 'optional, comma-separated - ClickHouse tables this job writes to, for the lineage view';
