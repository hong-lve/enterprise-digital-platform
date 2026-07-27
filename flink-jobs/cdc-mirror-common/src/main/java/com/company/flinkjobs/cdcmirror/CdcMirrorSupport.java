package com.company.flinkjobs.cdcmirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Shared Kafka+Debezium plumbing for the five cdc-mirror-* sink modules
 * (cdc-mirror-clickhouse/oracle/mysql/redis/doris) - each only differs in
 * how it writes a row out (JDBC dialect, upsert syntax, or Jedis for
 * Redis), so the source-side setup and Debezium envelope parsing live here
 * once instead of five times. Public (not package-private the way a
 * single-jar version of this would keep it) since the five sink modules
 * are now separate jars depending on this one as a library, not five
 * classes compiled together into one jar. Column set is fixed to the
 * test_orders_* demo schema (id/order_no/amount/status/created_at) - this
 * mirrors CdcTableSchemaService's declared Flink types for that schema
 * (id: BIGINT or DECIMAL depending on source engine, amount: DECIMAL,
 * created_at: BIGINT epoch millis - decimal.handling.mode=string on the
 * Debezium connector side means id/amount arrive as JSON strings, not
 * numbers, same as data-platform-service's own Flink SQL jobs rely on),
 * not a generic any-schema mirror - see TaskStatsJob's own javadoc for why
 * hand-written jars are scoped to one concrete job rather than a fully
 * dynamic schema.
 *
 * Plain Debezium JSON, not Avro - confirmed live that every currently-
 * registered CDC connector (mysqldemo-connector-v5/oracledemo-connector-v5)
 * uses org.apache.kafka.connect.json.JsonConverter, not Confluent's
 * AvroConverter, regardless of what got wired up in an earlier pass at this
 * (see git history) - an Avro-decoding source here would silently drop
 * every record (KafkaAvroDeserializer throws on non-Avro bytes, and the
 * row-extractor's own catch-and-return-null swallows that), producing zero
 * rows with no visible error. Match the deployment that's actually running,
 * not a config that was tried once and reverted.
 */
public final class CdcMirrorSupport {
    private CdcMirrorSupport() {
    }

    public static String arg(String[] args, String key, String defaultValue) {
        String prefix = "--" + key;
        for (int i = 0; i < args.length - 1; i++) {
            if (prefix.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    // The "kafka:9092" listener these jobs bootstrap against is SSL-only now
    // (see docker/bigdata/docker-compose.yml's x-kafka-common-env) - there's
    // no plaintext fallback to keep supporting, so this is unconditional
    // rather than another --arg. Truststore is bind-mounted into the
    // jobmanager/taskmanager containers at this fixed path (see those
    // services' volumes:); password comes from the same KAFKA_TLS_PASSWORD
    // env var docker-compose.yml already threads through to every other
    // Kafka client, not a literal in source.
    private static final String KAFKA_SSL_TRUSTSTORE_LOCATION = "/opt/flink/conf/kafka-truststore.p12";

    public static DataStream<Map<String, String>> sourceRows(StreamExecutionEnvironment env, String bootstrapServers, String topic, String groupId) {
        KafkaSource<String> source = KafkaSource.<String>builder()
            .setBootstrapServers(bootstrapServers)
            .setTopics(topic)
            .setGroupId(groupId)
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new NullSafeStringDeserializer())
            .setProperty("security.protocol", "SSL")
            .setProperty("ssl.truststore.location", KAFKA_SSL_TRUSTSTORE_LOCATION)
            .setProperty("ssl.truststore.type", "PKCS12")
            .setProperty("ssl.truststore.password", System.getenv().getOrDefault("KAFKA_TLS_PASSWORD", ""))
            .build();
        return env.fromSource(source, WatermarkStrategy.noWatermarks(), "cdc-source")
            .map(new DebeziumRowExtractor())
            .filter(row -> row != null);
    }

    /**
     * Debezium's tombstones.on.delete=true default means every DELETE
     * produces a normal delete changelog record AND a follow-up Kafka
     * tombstone (null value, the log-compaction "this key is gone"
     * convention) - SimpleStringSchema chokes on that with a
     * NullPointerException that crashes the source before
     * DebeziumRowExtractor's own null checks ever run. Same fix
     * TaskStatsJob already uses for the identical problem.
     */
    private static class NullSafeStringDeserializer implements DeserializationSchema<String> {
        @Override
        public String deserialize(byte[] message) {
            return message == null ? null : new String(message, StandardCharsets.UTF_8);
        }

        @Override
        public boolean isEndOfStream(String nextElement) {
            return false;
        }

        @Override
        public TypeInformation<String> getProducedType() {
            return Types.STRING;
        }
    }

    /**
     * Unwraps payload.after into a plain field-name (lowercased) -> text
     * value map so the same row shape works regardless of whether the
     * source engine's identifiers came through upper- or lower-case
     * (MySQL's test_orders_mysql is lowercase, Oracle's TEST_ORDERS_ORACLE
     * is upper-case). Skips delete/tombstone records (op != c/u/r) the same
     * way TaskStatsJob does - a mirror job has nothing meaningful to write
     * for a delete without also implementing a delete-aware sink, which
     * none of these five need for this demo schema.
     */
    private static class DebeziumRowExtractor implements MapFunction<String, Map<String, String>> {
        private transient ObjectMapper mapper;

        @Override
        public Map<String, String> map(String value) {
            if (mapper == null) {
                mapper = new ObjectMapper();
            }
            try {
                JsonNode root = mapper.readTree(value);
                JsonNode payload = root.has("payload") ? root.get("payload") : root;
                String op = payload.path("op").asText("");
                if (!"c".equals(op) && !"u".equals(op) && !"r".equals(op)) {
                    return null;
                }
                JsonNode after = payload.get("after");
                if (after == null || after.isNull()) {
                    return null;
                }
                Map<String, String> row = new LinkedHashMap<>();
                after.fields().forEachRemaining(entry -> {
                    JsonNode fieldValue = entry.getValue();
                    row.put(entry.getKey().toLowerCase(Locale.ROOT), fieldValue.isNull() ? null : fieldValue.asText());
                });
                return row;
            } catch (Exception exception) {
                return null;
            }
        }
    }
}
