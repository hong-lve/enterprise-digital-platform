#!/usr/bin/env bash
set -euo pipefail

# Backs up this stack's Kafka Connect topology - CDC topic configs
# (partition count, replication factor, per-topic overrides) and every
# registered Kafka Connect connector's full config (Debezium connectors
# included) - not topic message payloads. The CDC topics themselves are a
# replayable cache of MySQL/Oracle's own data (a connector re-registered
# with snapshot.mode=initial rebuilds a topic's contents from the source
# tables from scratch - this repo's whole session history of recreating
# topics/connectors after partition-count or version changes relies on
# exactly that), so what's actually irreplaceable and worth a backup story
# here is the *topology* - which topics exist with what partitioning, and
# which connectors are wired up with what credentials/table filters/
# converter settings - not the messages in flight through them.
#
# Usage: docker/bigdata/backup/kafka-backup.sh
# Produces docker/bigdata/backup/dumps/kafka-<timestamp>.tar.gz containing
# topics.txt (kafka-topics.sh --describe output for every CDC/business
# topic) and one <connector-name>.json per registered connector.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DUMP_DIR="$SCRIPT_DIR/dumps"
RETENTION_COUNT=14
KAFKA_CONTAINER=bigdata-kafka
CONNECT_CONTAINER=bigdata-kafka-connect
KAFKA_BIN=/opt/kafka/bin/kafka-topics.sh

mkdir -p "$DUMP_DIR"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="$DUMP_DIR/kafka-${TIMESTAMP}"
mkdir -p "$OUT_DIR"

echo "Listing topics ..."
# Internal/auto-managed topics (consumer offsets, Connect's own config/
# offset/status topics, Debezium's schema history, Schema Registry's own
# topic) are excluded - Kafka and Kafka Connect recreate these themselves;
# describing them wouldn't help a restore.
docker exec "$KAFKA_CONTAINER" "$KAFKA_BIN" --bootstrap-server localhost:9092 --list \
  | grep -vE '^(__consumer_offsets|_connect_configs|_connect_offsets|_connect_statuses|_schemas|schema-changes\.)' \
  > "$OUT_DIR/topic-list.txt"

echo "Backing up topic configs to topics.txt ..."
: > "$OUT_DIR/topics.txt"
while read -r topic; do
  [ -z "$topic" ] && continue
  docker exec "$KAFKA_CONTAINER" "$KAFKA_BIN" --bootstrap-server localhost:9092 --describe --topic "$topic" >> "$OUT_DIR/topics.txt"
done < "$OUT_DIR/topic-list.txt"
rm -f "$OUT_DIR/topic-list.txt"

echo "Backing up Kafka Connect connector configs ..."
CONNECTORS=$(docker exec "$CONNECT_CONTAINER" curl -s localhost:8083/connectors)
echo "$CONNECTORS" | grep -oE '"[^"]+"' | tr -d '"' | while read -r name; do
  [ -z "$name" ] && continue
  docker exec "$CONNECT_CONTAINER" curl -s "localhost:8083/connectors/${name}/config" > "$OUT_DIR/${name}.json"
  echo "  saved ${name}.json"
done

tar -C "$DUMP_DIR" -czf "$OUT_DIR.tar.gz" "kafka-${TIMESTAMP}"
rm -rf "$OUT_DIR"
echo "Backup written: $OUT_DIR.tar.gz ($(du -h "$OUT_DIR.tar.gz" | cut -f1))"

mapfile -t existing < <(ls -1t "$DUMP_DIR"/kafka-*.tar.gz 2>/dev/null)
if [ "${#existing[@]}" -gt "$RETENTION_COUNT" ]; then
  for old in "${existing[@]:$RETENTION_COUNT}"; do
    echo "Pruning old backup: $old"
    rm -f "$old"
  done
fi
