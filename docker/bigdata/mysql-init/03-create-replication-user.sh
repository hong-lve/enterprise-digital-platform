#!/bin/bash
# Runs once, on first boot only (same MySQL image behavior as
# 02-create-debezium-user.sh - only fires when the data directory is empty).
# Least-privilege account mysql-replica's CHANGE REPLICATION SOURCE TO
# authenticates as - REPLICATION SLAVE is the one privilege a replica
# connection actually needs, nothing else.
#
# .sh, not .sql - needs REPL_PASSWORD from the mysql service's own
# `environment:` (see docker-compose.yml) instead of a hardcoded literal;
# plain .sql files under docker-entrypoint-initdb.d are piped into the mysql
# client verbatim with no variable substitution.
set -eo pipefail

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
  -- mysql_native_password, not the server default (caching_sha2_password) -
  -- confirmed live that a replica's replication I/O thread fails full
  -- caching_sha2_password authentication over a plain (non-TLS) connection
  -- with "Authentication requires secure connection", even though normal
  -- client connections authenticate against the same account without issue.
  CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED WITH mysql_native_password BY '${REPL_PASSWORD}';
  GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
  FLUSH PRIVILEGES;
EOSQL
