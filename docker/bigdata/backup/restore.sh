#!/usr/bin/env bash
set -euo pipefail

# Restores a data_platform_db backup produced by backup.sh. Destructive -
# overwrites every table currently in the database with the dump's
# contents (anything created/changed since the backup was taken is lost,
# including Flyway's own migration history - restoring rolls the schema
# back to whatever it was at backup time too, not just the data). Requires
# an explicit --yes so this can't be fired off by a stray copy-paste.
#
# Usage: docker/bigdata/backup/restore.sh <path-to-dump.sql.gz> --yes

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER=bigdata-mysql
DATABASE=data_platform_db
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-123456}"

DUMP_FILE="${1:-}"
CONFIRM="${2:-}"

if [ -z "$DUMP_FILE" ] || [ ! -f "$DUMP_FILE" ]; then
  echo "Usage: $0 <path-to-dump.sql.gz> --yes"
  echo "Available backups:"
  ls -1t "$SCRIPT_DIR/dumps/${DATABASE}"-*.sql.gz 2>/dev/null || echo "  (none found)"
  exit 1
fi

if [ "$CONFIRM" != "--yes" ]; then
  echo "This will OVERWRITE every table in $DATABASE with the contents of $DUMP_FILE."
  echo "Re-run with --yes to confirm: $0 $DUMP_FILE --yes"
  exit 1
fi

echo "Restoring $DATABASE from $DUMP_FILE ..."
gunzip -c "$DUMP_FILE" | docker exec -i "$CONTAINER" mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$DATABASE"
echo "Restore complete."
