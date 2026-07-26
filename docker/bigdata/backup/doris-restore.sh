#!/usr/bin/env bash
set -euo pipefail

# Restores a Doris snapshot produced by doris-backup.sh. Restores every
# table the snapshot contains (no ON/EXCLUDE clause - confirmed live that
# Doris's RESTORE SNAPSHOT with neither clause just restores everything),
# either back into its original database (overwriting same-named tables in
# place, requires identical table structure) or into a different one you
# name explicitly (e.g. a scratch DB, to inspect a backup without touching
# live tables). Requires an explicit --yes so this can't be fired off by a
# stray copy-paste.
#
# Usage:
#   docker/bigdata/backup/doris-restore.sh <database> --list
#   docker/bigdata/backup/doris-restore.sh <target-database> <snapshot-name> --yes

DORIS_CONTAINER=bigdata-doris
REPO=doris_backup_repo

doris_sql() {
  docker exec "$DORIS_CONTAINER" mysql -h127.0.0.1 -P9030 -uroot -N -e "$1"
}

DATABASE="${1:-}"
SNAPSHOT="${2:-}"
CONFIRM="${3:-}"

if [ -z "$DATABASE" ] || [ -z "$SNAPSHOT" ] || [ "$SNAPSHOT" = "--list" ]; then
  echo "Usage:"
  echo "  $0 <database> --list                       # show available snapshots"
  echo "  $0 <target-database> <snapshot-name> --yes  # restore a snapshot"
  echo
  echo "Available snapshots in ${REPO}:"
  doris_sql "SHOW SNAPSHOT ON ${REPO};" || echo "  (repository ${REPO} not found - run doris-backup.sh at least once first)"
  exit 1
fi

if [ "$CONFIRM" != "--yes" ]; then
  echo "This restores snapshot '${SNAPSHOT}' into database '${DATABASE}'."
  echo "If ${DATABASE} already has same-named tables, they are OVERWRITTEN with the snapshot's contents."
  echo "Re-run with --yes to confirm: $0 $DATABASE $SNAPSHOT --yes"
  exit 1
fi

TS=$(doris_sql "SHOW SNAPSHOT ON ${REPO} WHERE SNAPSHOT = \"${SNAPSHOT}\";" | awk -F'\t' '{print $2}')
if [ -z "$TS" ] || [ "$TS" = "NULL" ]; then
  echo "Snapshot '${SNAPSHOT}' not found (or has no valid timestamp) in ${REPO}."
  echo "Available snapshots:"
  doris_sql "SHOW SNAPSHOT ON ${REPO};"
  exit 1
fi

doris_sql "CREATE DATABASE IF NOT EXISTS ${DATABASE};"

echo "Restoring snapshot ${SNAPSHOT} (timestamp ${TS}) into ${DATABASE} ..."
doris_sql "
  RESTORE SNAPSHOT ${DATABASE}.${SNAPSHOT}
  FROM ${REPO}
  PROPERTIES (\"backup_timestamp\"=\"${TS}\", \"replication_num\"=\"3\");
"

echo "Waiting for the restore job to finish ..."
STATE=""
for _ in $(seq 1 180); do
  STATE=$(doris_sql "SHOW RESTORE FROM ${DATABASE};" | awk -F'\t' -v s="$SNAPSHOT" '$2==s{print $5}')
  case "$STATE" in
    FINISHED) echo "Restore finished."; break ;;
    CANCELLED) echo "Restore was cancelled - check: SHOW RESTORE FROM ${DATABASE};"; exit 1 ;;
    *) sleep 2 ;;
  esac
done
if [ "$STATE" != "FINISHED" ]; then
  echo "Timed out waiting for restore to finish (last state: $STATE)."
  exit 1
fi

echo "Tables in ${DATABASE} now:"
doris_sql "SHOW TABLES FROM ${DATABASE};"
