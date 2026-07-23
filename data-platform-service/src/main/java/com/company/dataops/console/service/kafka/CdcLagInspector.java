package com.company.dataops.console.service.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Peeks at the latest message on a Kafka topic to estimate how stale a CDC
 * source's data is, without needing Debezium's JMX metrics (which would
 * need a Jolokia agent added to the kafka-connect container - more
 * infrastructure than this single-node environment needs). This is a
 * freshness proxy, NOT true binlog-position lag: it can't tell "connector
 * is stuck" apart from "MySQL just hasn't been written to in a while" -
 * both look identical (a growing gap since the last message). That's an
 * accepted limitation, not an oversight - see docker/bigdata/README.md.
 *
 * Uses a throwaway KafkaConsumer per call (assign(), not subscribe() - no
 * consumer group, no rebalance, nothing left running after close()) rather
 * than a long-lived consumer, since this only runs once per source per
 * CdcSourceStatusScheduler poll cycle (every 15s).
 */
@Component
public class CdcLagInspector {
    private static final Logger LOGGER = LoggerFactory.getLogger(CdcLagInspector.class);

    /** How many offsets to walk backward past the topic end looking for a non-tombstone record. */
    private static final int MAX_TOMBSTONE_LOOKBACK = 10;

    private final String bootstrapServers;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CdcLagInspector(@Value("${platform.bigdata.kafka-bootstrap-servers:localhost:19092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** Seconds since the most recent message's embedded MySQL event time, or null if the topic has no messages yet. */
    public Long freshnessSeconds(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "cdc-lag-inspector-" + System.nanoTime());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic, Duration.ofSeconds(5));
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return null;
            }
            List<TopicPartition> partitions = partitionInfos.stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions, Duration.ofSeconds(5));

            Long latestTimestamp = null;
            for (TopicPartition partition : partitions) {
                long endOffset = endOffsets.getOrDefault(partition, 0L);
                if (endOffset <= 0) {
                    continue; // empty partition
                }
                consumer.assign(Collections.singletonList(partition));
                Long sourceTimestamp = null;
                long lookbackAttempts = Math.min(MAX_TOMBSTONE_LOOKBACK, endOffset);
                for (long back = 0; back < lookbackAttempts; back++) {
                    long offset = endOffset - 1 - back;
                    consumer.seek(partition, offset);
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                    ConsumerRecord<String, String> record = records.records(partition).stream()
                        .filter(candidate -> candidate.offset() == offset)
                        .findFirst()
                        .orElse(null);
                    if (record == null || record.value() == null) {
                        continue; // couldn't read that offset, or it's a delete tombstone - walk further back
                    }
                    sourceTimestamp = extractSourceTimestamp(record.value());
                    break;
                }
                if (sourceTimestamp != null && (latestTimestamp == null || sourceTimestamp > latestTimestamp)) {
                    latestTimestamp = sourceTimestamp;
                }
            }
            return latestTimestamp == null ? null : Math.max(0, (System.currentTimeMillis() - latestTimestamp) / 1000);
        } catch (Exception exception) {
            LOGGER.warn("Failed to inspect lag for topic {}: {}", topic, exception.getMessage());
            return null;
        }
    }

    /** payload.source.ts_ms - the actual MySQL binlog event time, not payload.ts_ms (roughly "when Debezium emitted this"). */
    private Long extractSourceTimestamp(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            JsonNode source = payload.get("source");
            if (source == null) {
                return null;
            }
            JsonNode tsMs = source.get("ts_ms");
            return tsMs == null || tsMs.isNull() ? null : tsMs.asLong();
        } catch (Exception exception) {
            return null;
        }
    }
}
