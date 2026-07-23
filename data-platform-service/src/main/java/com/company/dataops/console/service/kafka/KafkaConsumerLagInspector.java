package com.company.dataops.console.service.kafka;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads real Kafka consumer group lag via AdminClient - unlike
 * CdcLagInspector (message-staleness proxy) and FlinkBackpressureInspector
 * (accumulated-time-delta proxy), this is the actual standard Kafka metric:
 * Flink's KafkaSource commits offsets back to Kafka after each checkpoint
 * when group.id is set (confirmed live via kafka-consumer-groups.sh
 * --describe against a running job's group - Flink itself doesn't need
 * these commits for its own recovery, that's what checkpoints are for, but
 * they're exactly what external tools like this are meant to read).
 */
@Component
public class KafkaConsumerLagInspector {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaConsumerLagInspector.class);

    private final String bootstrapServers;

    public KafkaConsumerLagInspector(@Value("${platform.bigdata.kafka-bootstrap-servers:localhost:19092}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** Total records behind across the given topics' partitions for this consumer group, or null if the group has no committed offsets yet (job never ran, or doesn't consume Kafka). */
    public Long totalLag(String groupId, List<String> topics) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);

        try (AdminClient adminClient = AdminClient.create(props)) {
            Map<TopicPartition, OffsetAndMetadata> committedOffsets = adminClient.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(5, TimeUnit.SECONDS);
            if (committedOffsets == null || committedOffsets.isEmpty()) {
                return null;
            }

            Set<String> relevantTopics = Set.copyOf(topics);
            Map<TopicPartition, OffsetAndMetadata> relevantCommitted = committedOffsets.entrySet().stream()
                .filter(entry -> relevantTopics.contains(entry.getKey().topic()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (relevantCommitted.isEmpty()) {
                return null;
            }

            Map<TopicPartition, OffsetSpec> latestRequest = relevantCommitted.keySet().stream()
                .collect(Collectors.toMap(partition -> partition, partition -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets = adminClient.listOffsets(latestRequest)
                .all()
                .get(5, TimeUnit.SECONDS);

            long totalLag = 0;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : relevantCommitted.entrySet()) {
                ListOffsetsResult.ListOffsetsResultInfo latest = latestOffsets.get(entry.getKey());
                if (latest != null) {
                    totalLag += Math.max(0, latest.offset() - entry.getValue().offset());
                }
            }
            return totalLag;
        } catch (Exception exception) {
            LOGGER.warn("Failed to inspect consumer lag for group {}: {}", groupId, exception.getMessage());
            return null;
        }
    }
}
