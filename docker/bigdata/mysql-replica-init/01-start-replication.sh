#!/bin/bash
# Runs once, on first boot only (same MySQL image behavior as mysql-init/'s
# scripts - only fires when this container's own data directory is empty).
# Points this instance at the primary as a replica using GTID auto-
# positioning (SOURCE_AUTO_POSITION=1) instead of a hand-tracked binlog
# file+offset - works whether this is a genuinely fresh pair (both empty,
# nothing to bridge) or this replica was bootstrapped from a
# `mysqldump --set-gtid-purged=ON` snapshot of an already-populated primary
# (see docker/bigdata/README.md's replication section for that one-time
# manual step - restoring an existing primary's data isn't something a
# docker-entrypoint-initdb.d script can do on its own since it only ever
# runs against its own empty container).
set -eo pipefail

until mysql -h mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" --connect-timeout=2 -e "SELECT 1" >/dev/null 2>&1; do
  echo "[mysql-replica-init] waiting for primary to accept connections..."
  sleep 2
done

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
  CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='mysql',
    SOURCE_USER='repl',
    SOURCE_PASSWORD='${REPL_PASSWORD}',
    SOURCE_AUTO_POSITION=1;
  START REPLICA;
EOSQL

echo "[mysql-replica-init] replication started"
