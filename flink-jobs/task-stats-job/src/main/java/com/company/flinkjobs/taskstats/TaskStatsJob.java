package com.company.flinkjobs.taskstats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;

/**
 * Example business job for realtime-compute-service's Flink 流作业 lifecycle
 * management: consumes the CDC topic for data-processing-platform-service's
 * data_task_log (task execution history), counts task results in tumbling
 * windows, writes the counts into ClickHouse. Exists to prove the whole
 * chain (CDC -> Kafka -> Flink -> ClickHouse) actually works end to end -
 * the platform itself doesn't ship this kind of business logic, see
 * docker/bigdata/README.md.
 *
 * ClickHouseStatsSink isn't part of Flink's checkpoint two-phase-commit
 * protocol, so a restart-from-checkpoint (crash, or a deliberate restart)
 * can recompute and re-send a window result that was already written
 * before the restart. task_execution_stats is a ReplacingMergeTree keyed
 * on (window_start, result) with updated_at as the version column so the
 * later physical write wins - re-sends are idempotent. That dedup only
 * happens at background-merge time though, so querying this table for a
 * correct count requires `SELECT ... FROM task_execution_stats FINAL`
 * (or an argMax-based GROUP BY); a plain SELECT can show duplicate rows
 * for a key that hasn't been merged away yet.
 */
public class TaskStatsJob {
    public static void main(String[] args) throws Exception {
        String bootstrapServers = getArg(args, "kafka-bootstrap", "kafka:9092");
        String topic = getArg(args, "topic", "demo.data_platform_db.data_task_log");
        String groupId = getArg(args, "group-id", "task-stats-job");
        String clickhouseUrl = getArg(args, "clickhouse-url", "jdbc:clickhouse://clickhouse:8123/realtime_analytics");
        String clickhouseUser = getArg(args, "clickhouse-user", "realtime");
        String clickhousePassword = getArg(args, "clickhouse-password", "realtime123");
        long windowSeconds = Long.parseLong(getArg(args, "window-seconds", "30"));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<String> source = KafkaSource.<String>builder()
            .setBootstrapServers(bootstrapServers)
            .setTopics(topic)
            .setGroupId(groupId)
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new NullSafeStringDeserializer())
            .build();

        DataStream<String> raw = env.fromSource(source, WatermarkStrategy.noWatermarks(), "cdc-source");

        DataStream<Tuple2<String, Long>> resultCounts = raw
            .map(new DebeziumResultExtractor())
            .filter(result -> result != null)
            .map(result -> Tuple2.of(result, 1L))
            .returns(Types.TUPLE(Types.STRING, Types.LONG))
            .keyBy(tuple -> tuple.f0)
            .window(TumblingProcessingTimeWindows.of(Time.seconds(windowSeconds)))
            .sum(1);

        resultCounts.addSink(new ClickHouseStatsSink(clickhouseUrl, clickhouseUser, clickhousePassword));

        env.execute("Task Execution Stats");
    }

    private static String getArg(String[] args, String key, String defaultValue) {
        String prefix = "--" + key;
        for (int i = 0; i < args.length - 1; i++) {
            if (prefix.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    /**
     * Debezium's default tombstones.on.delete=true means every DELETE on the
     * source table produces a normal delete changelog record AND a
     * follow-up Kafka tombstone (a record with a null value - the standard
     * log-compaction convention for "this key is gone"). SimpleStringSchema
     * doesn't guard against that: new String(null, ...) throws a
     * NullPointerException that crashes the source before any record ever
     * reaches DebeziumResultExtractor's own try/catch below, and burns
     * through the job's restart budget.
     *
     * Deliberately a plain flink-core DeserializationSchema (not
     * KafkaRecordDeserializationSchema, which references ConsumerRecord
     * directly) - this cluster also has a shaded flink-sql-connector-kafka
     * fat jar sitting in Flink's own lib/ for the SQL Gateway (see
     * docker/bigdata/docker-compose.yml), and implementing the
     * connector-level interface directly hit a real AbstractMethodError
     * from that shaded/unshaded ConsumerRecord clash when tested live.
     * Returning null from deserialize(byte[]) makes DeserializationSchema's
     * own default deserialize(byte[], Collector) skip forwarding it - Flink
     * already treats that as "no output for this record", so there's no
     * need to touch the Kafka connector's record-level API to skip
     * tombstones.
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

    /** Debezium wraps the row under payload.after (schemas.enable=true by default, so there's a schema key alongside payload - skip past it). */
    private static class DebeziumResultExtractor implements MapFunction<String, String> {
        private transient ObjectMapper mapper;

        @Override
        public String map(String value) {
            if (mapper == null) {
                mapper = new ObjectMapper();
            }
            try {
                JsonNode root = mapper.readTree(value);
                JsonNode payload = root.has("payload") ? root.get("payload") : root;
                String op = payload.path("op").asText("");
                // c=insert, u=update, r=initial snapshot read - skip d (delete) and tombstones (after is null there anyway)
                if (!"c".equals(op) && !"u".equals(op) && !"r".equals(op)) {
                    return null;
                }
                JsonNode after = payload.get("after");
                if (after == null || after.isNull()) {
                    return null;
                }
                JsonNode result = after.get("result");
                return result == null || result.isNull() ? null : result.asText();
            } catch (Exception exception) {
                return null;
            }
        }
    }

    private static class ClickHouseStatsSink extends RichSinkFunction<Tuple2<String, Long>> {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private transient Connection connection;
        private transient PreparedStatement statement;

        ClickHouseStatsSink(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            connection = DriverManager.getConnection(jdbcUrl, username, password);
            statement = connection.prepareStatement("INSERT INTO task_execution_stats (window_start, result, event_count, updated_at) VALUES (?, ?, ?, ?)");
        }

        @Override
        public void invoke(Tuple2<String, Long> value, Context context) {
            try {
                Long timestamp = context.timestamp();
                statement.setTimestamp(1, new Timestamp(timestamp == null ? System.currentTimeMillis() : timestamp));
                statement.setString(2, value.f0);
                statement.setLong(3, value.f1);
                // Version column for ReplacingMergeTree - the actual write time,
                // not the window time, so a re-send after checkpoint recovery
                // naturally gets a higher version than the write it duplicates.
                statement.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
                statement.executeUpdate();
            } catch (Exception exception) {
                throw new RuntimeException("写入 ClickHouse 失败", exception);
            }
        }

        @Override
        public void close() throws Exception {
            if (statement != null) {
                statement.close();
            }
            if (connection != null) {
                connection.close();
            }
        }
    }
}
