#!/usr/bin/env bash
set -euo pipefail

# Backs up this platform's own control-plane database (data_platform_db) -
# data source configs, CDC/Flink job definitions, users/roles/permissions,
# audit log, pending approvals - not the CDC business data flowing through
# the pipelines, which has its own upstream source of truth and isn't this
# platform's data to own a backup story for.
#
# Usage: docker/bigdata/backup/backup.sh
# Produces docker/bigdata/backup/dumps/data_platform_db-<timestamp>.sql.gz
# and prunes anything beyond the most recent RETENTION_COUNT dumps.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DUMP_DIR="$SCRIPT_DIR/dumps"
RETENTION_COUNT=14
CONTAINER=bigdata-mysql
DATABASE=data_platform_db
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-123456}"

mkdir -p "$DUMP_DIR"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
DUMP_FILE="$DUMP_DIR/${DATABASE}-${TIMESTAMP}.sql.gz"

echo "Backing up $DATABASE from container $CONTAINER to $DUMP_FILE ..."
# --single-transaction: consistent snapshot without locking tables (every
# table here is InnoDB, the MySQL 8 default). --set-gtid-purged=OFF: this
# dump is meant to be restorable into the same or a fresh local container,
# not replicated - embedded GTID state would only get in the way.
docker exec "$CONTAINER" mysqldump \
  -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --single-transaction \
  --routines \
  --triggers \
  --set-gtid-purged=OFF \
  "$DATABASE" | gzip > "$DUMP_FILE"

echo "Backup written: $DUMP_FILE ($(du -h "$DUMP_FILE" | cut -f1))"

mapfile -t existing < <(ls -1t "$DUMP_DIR"/"${DATABASE}"-*.sql.gz 2>/dev/null)
if [ "${#existing[@]}" -gt "$RETENTION_COUNT" ]; then
  for old in "${existing[@]:$RETENTION_COUNT}"; do
    echo "Pruning old backup: $old"
    rm -f "$old"
  done
fi
