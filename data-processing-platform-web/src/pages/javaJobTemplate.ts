// Starter template for JarPackagesPage.tsx's "在线编写" editor - a complete,
// working custom Flink job (mirrors a CDC topic into a JDBC table) so a user
// edits a real example instead of starting from a blank file. Uses
// CdcMirrorSupport (bundled into every online-compiled jar - see
// cdc-mirror-buildkit) for the shared Kafka+Debezium parsing boilerplate; only
// the sink logic in invoke() needs to change for a different target.
export const JAVA_JOB_TEMPLATE = `package com.company.userjobs;

import com.company.flinkjobs.cdcmirror.CdcMirrorSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

// 类名要跟下面"入口类全限定名"这一栏填的完全一致，包名可以随便改。
public class MyCustomJob {
    public static void main(String[] args) throws Exception {
        // 这些参数在"程序参数"里以 --key value 的形式传进来，跟 Flink 流作业
        // 页面里已有的几个 [JAR] 作业用的是同一套约定。
        String bootstrapServers = CdcMirrorSupport.arg(args, "kafka-bootstrap", "kafka:9092");
        String topic = CdcMirrorSupport.arg(args, "topic", "mysqldemo.cdc_demo.test_orders_mysql");
        String groupId = CdcMirrorSupport.arg(args, "group-id", "my-custom-job");
        String schemaRegistryUrl = CdcMirrorSupport.arg(args, "schema-registry-url", "http://schema-registry:8081");
        String sinkUrl = CdcMirrorSupport.arg(args, "sink-url", "jdbc:mysql://mysql:3306/cdc_demo");
        String sinkUser = CdcMirrorSupport.arg(args, "sink-user", "root");
        String sinkPassword = CdcMirrorSupport.arg(args, "sink-password", "");
        String sinkTable = CdcMirrorSupport.arg(args, "sink-table", "my_custom_sink");

        // "调试运行"时这些 println 会原样出现在下面的"调试输出"框里 - 点"调试
        // 运行"跑一下就知道参数对不对、连不连得上，不用等真提交到 Flink 集群才发现。
        System.out.println("[MyCustomJob] kafka=" + bootstrapServers + " topic=" + topic + " sink=" + sinkUrl);

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
            .addSink(new MySink(sinkUrl, sinkUser, sinkPassword, sinkTable));
        env.execute("My Custom Job");
    }

    // 把这里换成你自己要写的目标（ClickHouse/Oracle/Doris 也都是 JDBC，
    // 换个 URL 和 SQL 就行；参考已有的 cdc-mirror-* 系列 jar 源码）。
    private static class MySink extends RichSinkFunction<Map<String, String>> {
        private final String url;
        private final String user;
        private final String password;
        private final String table;
        private transient Connection connection;

        MySink(String url, String user, String password, String table) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.table = table;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            System.out.println("[MyCustomJob] connecting to " + url);
            connection = DriverManager.getConnection(url, user, password);
            System.out.println("[MyCustomJob] jdbc connected");
        }

        @Override
        public void invoke(Map<String, String> row, Context context) throws Exception {
            System.out.println("[MyCustomJob] writing row: " + row);
            String sql = "REPLACE INTO " + table + " (id, order_no, amount, status, created_at) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, row.get("id"));
                statement.setString(2, row.get("order_no"));
                statement.setString(3, row.get("amount"));
                statement.setString(4, row.get("status"));
                statement.setString(5, row.get("created_at"));
                statement.executeUpdate();
            }
        }

        @Override
        public void close() throws Exception {
            if (connection != null) {
                connection.close();
            }
        }
    }
}
`;
