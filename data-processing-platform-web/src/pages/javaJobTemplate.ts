// Starter template for JarPackagesPage.tsx's "在线编写" editor - a complete,
// working custom Flink job (mirrors a CDC topic into Redis) so a user edits
// a real example instead of starting from a blank file. Uses CdcMirrorSupport
// (bundled into every online-compiled jar - see cdc-mirror-buildkit) for the
// shared Kafka+Debezium parsing boilerplate; only the sink logic in invoke()
// needs to change for a different target.
export const JAVA_JOB_TEMPLATE = `package com.company.userjobs;

import com.company.flinkjobs.cdcmirror.CdcMirrorSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import redis.clients.jedis.Jedis;

// 类名要跟下面"入口类全限定名"这一栏填的完全一致，包名可以随便改。
public class MyCustomJob {
    public static void main(String[] args) throws Exception {
        // 这些参数在"程序参数"里以 --key value 的形式传进来，跟 Flink 流作业
        // 页面里已有的几个 [JAR] 作业用的是同一套约定。
        String bootstrapServers = CdcMirrorSupport.arg(args, "kafka-bootstrap", "kafka:9092");
        String topic = CdcMirrorSupport.arg(args, "topic", "mysqldemo.cdc_demo.test_orders_mysql");
        String groupId = CdcMirrorSupport.arg(args, "group-id", "my-custom-job");
        String schemaRegistryUrl = CdcMirrorSupport.arg(args, "schema-registry-url", "http://schema-registry:8081");
        String host = CdcMirrorSupport.arg(args, "sink-host", "redis");
        int port = Integer.parseInt(CdcMirrorSupport.arg(args, "sink-port", "6379"));
        String password = CdcMirrorSupport.arg(args, "sink-password", "redis123");
        String keyPrefix = CdcMirrorSupport.arg(args, "sink-key-prefix", "my_custom_sink");

        // "调试运行"时这些 println 会原样出现在下面的"调试输出"框里 - 点"调试
        // 运行"跑一下就知道参数对不对、连不连得上，不用等真提交到 Flink 集群才发现。
        System.out.println("[MyCustomJob] kafka=" + bootstrapServers + " topic=" + topic + " sink=" + host + ":" + port);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // sourceRows() already parses the Debezium CDC envelope for you -
        // each row here is a lower-cased column-name -> text-value Map
        // (id/order_no/amount/status/created_at for the demo test_orders_*
        // schema), deletes/tombstones already filtered out. Envelopes are
        // Avro now (Kafka Connect's converters + Confluent Schema Registry),
        // not plain JSON - sourceRows() resolves each record's schema from
        // the registry by the id embedded in the message, so this still
        // works across tables without knowing any schema ahead of time.
        CdcMirrorSupport.sourceRows(env, bootstrapServers, topic, groupId, schemaRegistryUrl)
            .addSink(new MySink(host, port, password, keyPrefix));
        env.execute("My Custom Job");
    }

    // 把这里换成你自己要写的目标（改用 JDBC 写 MySQL/ClickHouse/Oracle/Doris 都行，
    // 参考已有的 cdc-mirror-* 系列 jar 源码）。
    private static class MySink extends RichSinkFunction<Map<String, String>> {
        private final String host;
        private final int port;
        private final String password;
        private final String keyPrefix;
        private transient Jedis jedis;

        MySink(String host, int port, String password, String keyPrefix) {
            this.host = host;
            this.port = port;
            this.password = password;
            this.keyPrefix = keyPrefix;
        }

        @Override
        public void open(Configuration parameters) {
            System.out.println("[MyCustomJob] connecting to redis " + host + ":" + port);
            jedis = new Jedis(host, port);
            if (password != null && !password.isBlank()) {
                jedis.auth(password);
            }
            System.out.println("[MyCustomJob] redis connected");
        }

        @Override
        public void invoke(Map<String, String> row, Context context) {
            String key = keyPrefix + ":" + row.get("id");
            System.out.println("[MyCustomJob] writing row: " + row);
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
`;
