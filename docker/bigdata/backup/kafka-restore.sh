#!/usr/bin/env bash
set -euo pipefail

# Restores topics + Kafka Connect connectors from a kafka-backup.sh archive.
#
# Topics: creates any topic from topics.txt that doesn't already exist,
# with the same partition count/replication factor/configs it had at
# backup time. Never touches a topic that's already there - altering
# partition count on a live keyed topic breaks its key->partition hash
# mapping (see docker/bigdata/README.md), so this only fills in what's
# missing rather than trying to reconcile drift.
#
# Connectors: re-registers each saved <name>.json via PUT (create-or-
# update - unlike POST /connectors, which 409s if the name already
# exists), which then runs a fresh Debezium initial snapshot against
# whatever the source database looks like right now.
#
# Usage: docker/bigdata/backup/kafka-restore.sh <path-to-kafka-*.tar.gz> --yes

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KAFKA_CONTAINER=bigdata-kafka
CONNECT_CONTAINER=bigdata-kafka-connect
KAFKA_BIN=/opt/kafka/bin/kafka-topics.sh

ARCHIVE="${1:-}"
CONFIRM="${2:-}"

if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
  echo "Usage: $0 <path-to-kafka-*.tar.gz> --yes"
  echo "Available backups:"
  ls -1t "$SCRIPT_DIR/dumps/kafka-"*.tar.gz 2>/dev/null || echo "  (none found)"
  exit 1
fi

if [ "$CONFIRM" != "--yes" ]; then
  echo "This creates any missing topic found in $ARCHIVE and re-registers every connector it contains (each one runs a fresh CDC snapshot)."
  echo "Re-run with --yes to confirm: $0 $ARCHIVE --yes"
  exit 1
fi

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
tar -C "$WORK_DIR" -xzf "$ARCHIVE"
EXTRACTED_DIR=$(find "$WORK_DIR" -mindepth 1 -maxdepth 1 -type d)

echo "Restoring topics from $(basename "$EXTRACTED_DIR")/topics.txt ..."
EXISTING_TOPICS=$(docker exec "$KAFKA_CONTAINER" "$KAFKA_BIN" --bootstrap-server localhost:9092 --list)

# Each topic's kafka-topics.sh --describe output is one summary line
# (identifiable by its 2nd tab-separated field starting with "TopicId:")
# followed by one line per partition (2nd field is "Partition:" instead) -
# only the summary line is needed to recreate the topic.
awk -F'\t' '$2 ~ /^TopicId:/' "$EXTRACTED_DIR/topics.txt" | while IFS=$'\t' read -r f_topic _ f_part f_repl f_conf; do
  TOPIC="${f_topic#Topic: }"
  PARTITIONS="${f_part#PartitionCount: }"
  REPLICATION="${f_repl#ReplicationFactor: }"
  CONFIGS="${f_conf#Configs: }"

  if echo "$EXISTING_TOPICS" | grep -qxF "$TOPIC"; then
    echo "  $TOPIC already exists, leaving it alone."
    continue
  fi

  CONFIG_ARGS=()
  if [ -n "$CONFIGS" ]; then
    IFS=',' read -ra PAIRS <<< "$CONFIGS"
    for pair in "${PAIRS[@]}"; do
      CONFIG_ARGS+=(--config "$pair")
    done
  fi

  echo "  creating $TOPIC (partitions=$PARTITIONS, replication=$REPLICATION)"
  docker exec "$KAFKA_CONTAINER" "$KAFKA_BIN" --bootstrap-server localhost:9092 --create \
    --topic "$TOPIC" --partitions "$PARTITIONS" --replication-factor "$REPLICATION" "${CONFIG_ARGS[@]}"
done

echo "Restoring Kafka Connect connectors ..."
for cfg in "$EXTRACTED_DIR"/*.json; do
  [ -e "$cfg" ] || continue
  name=$(basename "$cfg" .json)
  echo "  registering $name ..."
  docker cp "$cfg" "$CONNECT_CONTAINER:/tmp/${name}.json"
  docker exec "$CONNECT_CONTAINER" sh -c "curl -s -X PUT -H 'Content-Type: application/json' --data @/tmp/${name}.json localhost:8083/connectors/${name}/config"
  echo
  # Best-effort only - the connect container's own user doesn't always own
  # files docker cp lands as root, so this can fail with "Operation not
  # permitted"; that's just a leftover temp file, not a reason to abort the
  # rest of the restore.
  docker exec "$CONNECT_CONTAINER" rm -f "/tmp/${name}.json" || true
done

echo "Restore complete."
