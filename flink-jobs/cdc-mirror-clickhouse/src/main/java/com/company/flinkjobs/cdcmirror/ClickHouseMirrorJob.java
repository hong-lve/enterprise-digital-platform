package com.company.flinkjobs.cdcmirror;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

/**
 * Mirrors a test_orders_* CDC topic into a ClickHouse ReplacingMergeTree
 * table (ORDER BY id, so a re-sent row after checkpoint recovery is just a
 * later version that a background merge dedups - no delete/update
 * statement needed, plain INSERT is correct here same as
 * TaskStatsJob.ClickHouseStatsSink already relies on).
 */
public class ClickHouseMirrorJob {
    public static void main(String[] args) throws Exception {
        String bootstrapServers = CdcMirrorSupport.arg(args, "kafka-bootstrap", "kafka:9092");
        String topic = CdcMirrorSupport.arg(args, "topic", "mysqldemo.cdc_demo.test_orders_mysql");
        String groupId = CdcMirrorSupport.arg(args, "group-id", "cdc-mirror-clickhouse");
        String schemaRegistryUrl = CdcMirrorSupport.arg(args, "schema-registry-url", "http://schema-registry:8081");
        String url = CdcMirrorSupport.arg(args, "sink-url", "jdbc:clickhouse://clickhouse:8123/realtime_analytics");
        String user = CdcMirrorSupport.arg(args, "sink-user", "realtime");
        String password = CdcMirrorSupport.arg(args, "sink-password", "realtime123");
        String table = CdcMirrorSupport.arg(args, "sink-table", "test_orders_mysql_ch_sink");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        CdcMirrorSupport.sourceRows(env, bootstrapServers, topic, groupId, schemaRegistryUrl)
            .addSink(new ClickHouseRowSink(url, user, password, table));
        env.execute("CDC Mirror -> ClickHouse (" + table + ")");
    }

    private static class ClickHouseRowSink extends RichSinkFunction<Map<String, String>> {
        private final String url;
        private final String user;
        private final String password;
        private final String table;
        private transient Connection connection;
        private transient PreparedStatement statement;

        ClickHouseRowSink(String url, String user, String password, String table) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.table = table;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            // Flink runs each job's jar in its own user-code classloader,
            // separate from whatever classloader java.sql.DriverManager's
            // static ServiceLoader-based discovery ran under - so the
            // META-INF/services/java.sql.Driver entry shaded into this jar
            // (confirmed present) never gets picked up automatically, and
            // getConnection() fails with "No suitable driver found" even
            // though the driver class is right there on the classpath.
            // Explicitly loading it registers it against the *current*
            // classloader, which is the standard fix for this in any
            // isolated-classloader environment (app servers, OSGi, and here).
            Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
            connection = DriverManager.getConnection(url, user, password);
            statement = connection.prepareStatement(
                "INSERT INTO " + table + " (id, order_no, amount, status, created_at) VALUES (?, ?, ?, ?, ?)");
        }

        @Override
        public void invoke(Map<String, String> row, Context context) throws Exception {
            statement.setBigDecimal(1, new java.math.BigDecimal(row.get("id")));
            statement.setString(2, row.get("order_no"));
            statement.setBigDecimal(3, new java.math.BigDecimal(row.get("amount")));
            statement.setString(4, row.get("status"));
            statement.setLong(5, Long.parseLong(row.get("created_at")));
            statement.executeUpdate();
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
