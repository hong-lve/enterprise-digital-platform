-- Lets a jar be referred to by a meaningful name distinct from its raw
-- uploaded filename (e.g. "任务统计作业" instead of "task-stats-job.jar") -
-- the Flink 流作业 jar-selection dropdown shows this instead of the filename.
ALTER TABLE flink_jar ADD COLUMN name VARCHAR(200) NULL AFTER id;
UPDATE flink_jar SET name = original_name WHERE name IS NULL;
ALTER TABLE flink_jar MODIFY COLUMN name VARCHAR(200) NOT NULL;
