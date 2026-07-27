package com.company.flinkjobs.cdcmirror;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import redis.clients.jedis.Jedis;

/**
 * Mirrors a test_orders_* CDC topic into Redis as a hash per row, keyed
 * "<sink-table>:<id>" (matching SinkTableDdlBuilder.redisWithClause()'s
 * key-prefix convention for the declarative SQL 流作业 case, so both paths
 * land in a predictable, namespaced key shape). HSET is naturally
 * idempotent - a re-sent row after checkpoint recovery just overwrites the
 * same fields with the same values, no separate upsert logic needed the
 * way the JDBC sinks require.
 */
public class RedisMirrorJob {
    public static void main(String[] args) throws Exception {
        String bootstrapServers = CdcMirrorSupport.arg(args, "kafka-bootstrap", "kafka:9092");
        String topic = CdcMirrorSupport.arg(args, "topic", "mysqldemo.cdc_demo.test_orders_mysql");
        String groupId = CdcMirrorSupport.arg(args, "group-id", "cdc-mirror-redis");
        String host = CdcMirrorSupport.arg(args, "sink-host", "redis");
        int port = Integer.parseInt(CdcMirrorSupport.arg(args, "sink-port", "6379"));
        String password = CdcMirrorSupport.arg(args, "sink-password", "redis123");
        String keyPrefix = CdcMirrorSupport.arg(args, "sink-key-prefix", "test_orders_mysql_redis_sink");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        CdcMirrorSupport.sourceRows(env, bootstrapServers, topic, groupId)
            .addSink(new RedisRowSink(host, port, password, keyPrefix));
        env.execute("CDC Mirror -> Redis (" + keyPrefix + ")");
    }

    private static class RedisRowSink extends RichSinkFunction<Map<String, String>> {
        private final String host;
        private final int port;
        private final String password;
        private final String keyPrefix;
        private transient Jedis jedis;

        RedisRowSink(String host, int port, String password, String keyPrefix) {
            this.host = host;
            this.port = port;
            this.password = password;
            this.keyPrefix = keyPrefix;
        }

        @Override
        public void open(Configuration parameters) {
            jedis = new Jedis(host, port);
            if (password != null && !password.isBlank()) {
                jedis.auth(password);
            }
        }

        @Override
        public void invoke(Map<String, String> row, Context context) {
            String key = keyPrefix + ":" + row.get("id");
            Map<String, String> fields = new LinkedHashMap<>(row);
            fields.values().removeIf(java.util.Objects::isNull);
            jedis.hset(key, fields);
        }

        @Override
        public void close() {
            if (jedis != null) {
                jedis.close();
            }
        }
    }
}
