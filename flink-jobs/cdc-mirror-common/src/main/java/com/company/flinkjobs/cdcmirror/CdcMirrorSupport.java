package com.company.flinkjobs.cdcmirror;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.apache.avro.generic.GenericRecord;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Shared Kafka+Debezium plumbing for the four cdc-mirror-* sink modules
 * (cdc-mirror-clickhouse/oracle/mysql/doris) - each only differs in
 * how it writes a row out (JDBC dialect, upsert syntax), so the
 * source-side setup and Debezium envelope parsing live here
 * once instead of four times. Public (not package-private the way a
 * single-jar version of this would keep it) since the four sink modules
 * are now separate jars depending on this one as a library, not four
 * classes compiled together into one jar. Column set is fixed to the
 * test_orders_* demo schema (id/order_no/amount/status/created_at) - this
 * mirrors CdcTableSchemaService's declared Flink types for that schema
 * (id: BIGINT or DECIMAL depending on source engine, amount: DECIMAL,
 * created_at: BIGINT epoch millis - decimal.handling.mode=string on the
 * Debezium connector side means id/amount arrive as plain strings regardless
 * of whether the wire format is JSON or Avro, same as data-processing-platform-service's
 * own Flink SQL jobs rely on), not a generic any-schema mirror - see
 * TaskStatsJob's own javadoc for why hand-written jars are scoped to one
 * concrete job rather than a fully dynamic schema.
 *
 * Kafka Connect's converters now emit Avro (Confluent wire format: magic
 * byte + 4-byte schema id + Avro binary) instead of plain Debezium JSON, with
 * every CDC topic's schema registered in Confluent Schema Registry - this is
 * a real schema-enforcement boundary (a producer-side field type change that
 * isn't a compatible schema evolution gets rejected at the registry, not
 * discovered downstream as a silently-corrupted row), not just a catalog of
 * what the JSON happens to look like today.
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

    public static DataStream<Map<String, String>> sourceRows(StreamExecutionEnvironment env, String bootstrapServers, String topic, String groupId, String schemaRegistryUrl) {
        KafkaSource<byte[]> source = KafkaSource.<byte[]>builder()
            .setBootstrapServers(bootstrapServers)
            .setTopics(topic)
            .setGroupId(groupId)
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new NullSafeByteArrayDeserializer())
            .setProperty("security.protocol", "SSL")
            .setProperty("ssl.truststore.location", KAFKA_SSL_TRUSTSTORE_LOCATION)
            .setProperty("ssl.truststore.type", "PKCS12")
            .setProperty("ssl.truststore.password", System.getenv().getOrDefault("KAFKA_TLS_PASSWORD", ""))
            .build();
        return env.fromSource(source, WatermarkStrategy.noWatermarks(), "cdc-source")
            .map(new DebeziumAvroRowExtractor(topic, schemaRegistryUrl))
            .filter(row -> row != null);
    }

    /**
     * Debezium's tombstones.on.delete=true default means every DELETE
     * produces a normal delete changelog record AND a follow-up Kafka
     * tombstone (null value, the log-compaction "this key is gone"
     * convention) - a plain byte[] deserializer that chokes on null would
     * crash the source before DebeziumAvroRowExtractor's own null check ever
     * runs. Same fix TaskStatsJob already used for the identical problem
     * back when this was a String deserializer.
     */
    private static class NullSafeByteArrayDeserializer implements DeserializationSchema<byte[]> {
        @Override
        public byte[] deserialize(byte[] message) {
            return message;
        }

        @Override
        public boolean isEndOfStream(byte[] nextElement) {
            return false;
        }

        @Override
        public TypeInformation<byte[]> getProducedType() {
            return org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO;
        }
    }

    /**
     * Decodes Kafka Connect's AvroConverter wire format (a leading magic
     * byte + 4-byte schema id registered in Confluent Schema Registry,
     * followed by the Avro binary payload) via the same
     * KafkaAvroDeserializer Confluent's own consumers use - it resolves the
     * writer schema by id from the registry itself at runtime, so this
     * class doesn't need to know any table's schema ahead of time, the same
     * dynamic-row-shape behavior the old Jackson JsonNode-based JSON parsing
     * had. Unwraps envelope.after into a plain field-name (lowercased) ->
     * text value map so the same row shape works regardless of whether the
     * source engine's identifiers came through upper- or lower-case
     * (MySQL's test_orders_mysql is lowercase, Oracle's TEST_ORDERS_ORACLE
     * is upper-case). Skips delete/tombstone records (op != c/u/r) the same
     * way TaskStatsJob does - a mirror job has nothing meaningful to write
     * for a delete without also implementing a delete-aware sink, which
     * none of these four need for this demo schema. Unlike the JSON
     * envelope (which nests op/after under a "payload" key alongside a
     * sibling "schema" key), Avro has no such wrapper - the schema lives in
     * the registry instead, so op/after sit directly on the decoded record.
     */
    // Package-private (not private) so CdcMirrorSupportTest/DebeziumAvroRowExtractorTest
    // can construct one directly with a MockSchemaRegistryClient-backed deserializer and
    // assert real decoded field values - see the test's own javadoc for why this specific
    // class is the one place a regression here (e.g. reverting to JSON-style parsing of
    // what's actually Confluent-wire-format Avro) needs a pinning test.
    static class DebeziumAvroRowExtractor implements MapFunction<byte[], Map<String, String>> {
        private final String topic;
        private final String schemaRegistryUrl;
        private transient KafkaAvroDeserializer deserializer;

        DebeziumAvroRowExtractor(String topic, String schemaRegistryUrl) {
            this.topic = topic;
            this.schemaRegistryUrl = schemaRegistryUrl;
        }

        // Test-only entry point: production always goes through the lazy-init path
        // above (real schema-registry-url), this one takes an already-configured
        // deserializer (backed by MockSchemaRegistryClient in tests) so map() skips
        // straight to decoding without needing a real registry over HTTP.
        DebeziumAvroRowExtractor(String topic, KafkaAvroDeserializer deserializer) {
            this.topic = topic;
            this.schemaRegistryUrl = null;
            this.deserializer = deserializer;
        }

        @Override
        public Map<String, String> map(byte[] value) {
            if (value == null) {
                return null;
            }
            if (deserializer == null) {
                Map<String, Object> config = new HashMap<>();
                config.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
                config.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false);
                deserializer = new KafkaAvroDeserializer();
                deserializer.configure(config, false);
            }
            try {
                Object decoded = deserializer.deserialize(topic, value);
                if (!(decoded instanceof GenericRecord envelope)) {
                    return null;
                }
                Object opValue = envelope.get("op");
                String op = opValue == null ? "" : opValue.toString();
                if (!"c".equals(op) && !"u".equals(op) && !"r".equals(op)) {
                    return null;
                }
                Object afterValue = envelope.get("after");
                if (!(afterValue instanceof GenericRecord after)) {
                    return null;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (org.apache.avro.Schema.Field field : after.getSchema().getFields()) {
                    Object fieldValue = after.get(field.name());
                    row.put(field.name().toLowerCase(Locale.ROOT), fieldValue == null ? null : fieldValue.toString());
                }
                return row;
            } catch (Exception exception) {
                return null;
            }
        }
    }
}
