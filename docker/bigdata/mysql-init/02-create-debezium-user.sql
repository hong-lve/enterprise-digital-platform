-- Runs once, on first boot only (see 01-restore.sql's own note - MySQL's
-- official image only executes /docker-entrypoint-initdb.d/* when the data
-- directory is empty). Generically useful on any install, not just this
-- one's - unlike 01-restore.sql (a personal, environment-specific data dump,
-- gitignored), this file is meant to be tracked and shipped as-is.
--
-- Least-privilege account for Debezium/Kafka Connect CDC, matching this
-- project's docker/bigdata/README.md "CDC 前置条件" section - not the root
-- account.
CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'debezium123';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
