package com.company.flinkjobs.cdcmirror;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

/**
 * Mirrors a test_orders_* CDC topic into a MySQL table via a real upsert
 * (ON DUPLICATE KEY UPDATE) - unlike ClickHouse's ReplacingMergeTree, a
 * plain MySQL table has no background dedup, so a re-sent row after
 * checkpoint recovery would otherwise duplicate-key-error rather than
 * silently correct itself.
 */
public class MySqlMirrorJob {
    public static void main(String[] args) throws Exception {
        String bootstrapServers = CdcMirrorSupport.arg(args, "kafka-bootstrap", "kafka:9092");
        String topic = CdcMirrorSupport.arg(args, "topic", "oracledemo.REALTIME.TEST_ORDERS_ORACLE");
        String groupId = CdcMirrorSupport.arg(args, "group-id", "cdc-mirror-mysql");
        String url = CdcMirrorSupport.arg(args, "sink-url", "jdbc:mysql://mysql:3306/cdc_demo");
        String user = CdcMirrorSupport.arg(args, "sink-user", "root");
        String password = CdcMirrorSupport.arg(args, "sink-password", "123456");
        String table = CdcMirrorSupport.arg(args, "sink-table", "test_orders_oracle_mysql_sink");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        CdcMirrorSupport.sourceRows(env, bootstrapServers, topic, groupId)
            .addSink(new MySqlRowSink(url, user, password, table));
        env.execute("CDC Mirror -> MySQL (" + table + ")");
    }

    private static class MySqlRowSink extends RichSinkFunction<Map<String, String>> {
        private final String url;
        private final String user;
        private final String password;
        private final String table;
        private transient Connection connection;
        private transient PreparedStatement statement;

        MySqlRowSink(String url, String user, String password, String table) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.table = table;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            // See ClickHouseMirrorJob's identical open() comment - Flink's
            // per-job classloader isolation means DriverManager's automatic
            // ServiceLoader discovery misses this shaded driver too.
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(url, user, password);
            statement = connection.prepareStatement(
                "INSERT INTO " + table + " (id, order_no, amount, status, created_at) VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE order_no = VALUES(order_no), amount = VALUES(amount), status = VALUES(status), created_at = VALUES(created_at)");
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
