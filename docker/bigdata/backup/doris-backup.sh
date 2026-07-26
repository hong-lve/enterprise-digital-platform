#!/usr/bin/env bash
set -euo pipefail

# Backs up every table in a Doris database using Doris's own native
# BACKUP SNAPSHOT mechanism - unlike backup.sh (mysqldump against MySQL's
# control-plane data), Doris's storage format isn't something a generic SQL
# dump tool understands, so this drives Doris's built-in backup/restore
# instead, backed by a repository living in this stack's own MinIO
# instance. Bootstraps that repository (and its MinIO bucket) on first run
# if it doesn't exist yet - safe to re-run, does nothing if already there.
#
# Usage: docker/bigdata/backup/doris-backup.sh [database]
# Defaults to realtime_demo (the app's only Doris sink database). Produces
# a timestamped snapshot inside the doris_backup_repo repository (object
# storage, not a local file - there's nothing under dumps/ to look for).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DORIS_CONTAINER=bigdata-doris
MINIO_CONTAINER=bigdata-minio
REPO=doris_backup_repo
BUCKET=doris-backup
RETENTION_COUNT=14
DATABASE="${1:-realtime_demo}"

doris_sql() {
  docker exec "$DORIS_CONTAINER" mysql -h127.0.0.1 -P9030 -uroot -N -e "$1"
}

# --- bootstrap the repository if this is the first run ---
if ! doris_sql "SHOW REPOSITORIES;" | awk -F'\t' -v r="$REPO" '$2==r{found=1} END{exit !found}'; then
  echo "Repository ${REPO} not registered yet - bootstrapping it against MinIO ..."
  # shellcheck source=/dev/null
  source "$SCRIPT_DIR/../.env"
  docker exec "$MINIO_CONTAINER" sh -c "mc alias set local http://localhost:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD && mc mb --ignore-existing local/${BUCKET}"
  doris_sql "
    CREATE REPOSITORY ${REPO}
    WITH S3
    ON LOCATION \"s3://${BUCKET}/backup\"
    PROPERTIES (
      \"s3.endpoint\" = \"http://minio:9000\",
      \"s3.region\" = \"us-east-1\",
      \"s3.access_key\" = \"${MINIO_ROOT_USER}\",
      \"s3.secret_key\" = \"${MINIO_ROOT_PASSWORD}\",
      \"use_path_style\" = \"true\"
    );
  "
fi

TABLES=$(doris_sql "SHOW TABLES FROM ${DATABASE};" | tr '\n' ',' | sed 's/,$//')
if [ -z "$TABLES" ]; then
  echo "No tables found in ${DATABASE}, nothing to back up."
  exit 0
fi

TIMESTAMP=$(date +%Y%m%d%H%M%S)
SNAPSHOT="${DATABASE}_snapshot_${TIMESTAMP}"

echo "Backing up ${DATABASE} (${TABLES}) to snapshot ${SNAPSHOT} ..."
doris_sql "BACKUP SNAPSHOT ${DATABASE}.${SNAPSHOT} TO ${REPO} ON (${TABLES});"

echo "Waiting for the backup job to finish ..."
STATE=""
for _ in $(seq 1 120); do
  STATE=$(doris_sql "SHOW BACKUP FROM ${DATABASE};" | awk -F'\t' -v s="$SNAPSHOT" '$2==s{print $4}')
  case "$STATE" in
    FINISHED) echo "Backup finished."; break ;;
    CANCELLED) echo "Backup was cancelled - check: SHOW BACKUP FROM ${DATABASE};"; exit 1 ;;
    *) sleep 2 ;;
  esac
done
if [ "$STATE" != "FINISHED" ]; then
  echo "Timed out waiting for backup to finish (last state: $STATE)."
  exit 1
fi

# Doris has no DROP SNAPSHOT command to prune old snapshots from a
# repository - each snapshot's backing objects live entirely under its own
# __ss_<name>/ prefix (confirmed live), so pruning means deleting that
# prefix directly from the bucket instead.
mapfile -t existing < <(doris_sql "SHOW SNAPSHOT ON ${REPO} WHERE SNAPSHOT LIKE \"${DATABASE}_snapshot_%\";" | awk -F'\t' '{print $1}' | sort)
if [ "${#existing[@]}" -gt "$RETENTION_COUNT" ]; then
  # shellcheck source=/dev/null
  [ -f "$SCRIPT_DIR/../.env" ] && source "$SCRIPT_DIR/../.env"
  docker exec "$MINIO_CONTAINER" sh -c "mc alias set local http://localhost:9000 \$MINIO_ROOT_USER \$MINIO_ROOT_PASSWORD" >/dev/null
  for old in "${existing[@]:0:$((${#existing[@]} - RETENTION_COUNT))}"; do
    echo "Pruning old snapshot: $old"
    docker exec "$MINIO_CONTAINER" sh -c "mc rm -r --force local/${BUCKET}/backup/__palo_repository_${REPO}/__ss_${old}" >/dev/null
  done
fi

echo "Done. Snapshots currently in ${REPO} for ${DATABASE}:"
doris_sql "SHOW SNAPSHOT ON ${REPO} WHERE SNAPSHOT LIKE \"${DATABASE}_snapshot_%\";"
