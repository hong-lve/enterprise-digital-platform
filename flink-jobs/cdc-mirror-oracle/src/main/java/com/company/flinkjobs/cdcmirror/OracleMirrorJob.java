package com.company.flinkjobs.cdcmirror;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

/**
 * Mirrors a test_orders_* CDC topic into an Oracle table via MERGE INTO -
 * Oracle has no INSERT ... ON DUPLICATE KEY UPDATE equivalent, MERGE is the
 * standard way to get the same "insert or update by key" behavior a
 * re-sent row after checkpoint recovery needs.
 */
public class OracleMirrorJob {
    public static void main(String[] args) throws Exception {
        String bootstrapServers = CdcMirrorSupport.arg(args, "kafka-bootstrap", "kafka:9092");
        String topic = CdcMirrorSupport.arg(args, "topic", "mysqldemo.cdc_demo.test_orders_mysql");
        String groupId = CdcMirrorSupport.arg(args, "group-id", "cdc-mirror-oracle");
        String url = CdcMirrorSupport.arg(args, "sink-url", "jdbc:oracle:thin:@//oracle:1521/FREE");
        String user = CdcMirrorSupport.arg(args, "sink-user", "system");
        String password = CdcMirrorSupport.arg(args, "sink-password", "oracle123");
        String table = CdcMirrorSupport.arg(args, "sink-table", "test_orders_mysql_oracle_sink");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        CdcMirrorSupport.sourceRows(env, bootstrapServers, topic, groupId)
            .addSink(new OracleRowSink(url, user, password, table));
        env.execute("CDC Mirror -> Oracle (" + table + ")");
    }

    private static class OracleRowSink extends RichSinkFunction<Map<String, String>> {
        private final String url;
        private final String user;
        private final String password;
        private final String table;
        private transient Connection connection;
        private transient PreparedStatement statement;

        OracleRowSink(String url, String user, String password, String table) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.table = table;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            // See ClickHouseMirrorJob's identical open() comment.
            Class.forName("oracle.jdbc.OracleDriver");
            connection = DriverManager.getConnection(url, user, password);
            statement = connection.prepareStatement(
                "MERGE INTO " + table + " t USING (SELECT ? AS id FROM DUAL) s ON (t.id = s.id) "
                    + "WHEN MATCHED THEN UPDATE SET order_no = ?, amount = ?, status = ?, created_at = ? "
                    + "WHEN NOT MATCHED THEN INSERT (id, order_no, amount, status, created_at) VALUES (?, ?, ?, ?, ?)");
        }

        @Override
        public void invoke(Map<String, String> row, Context context) throws Exception {
            java.math.BigDecimal id = new java.math.BigDecimal(row.get("id"));
            String orderNo = row.get("order_no");
            java.math.BigDecimal amount = new java.math.BigDecimal(row.get("amount"));
            String status = row.get("status");
            long createdAt = Long.parseLong(row.get("created_at"));

            statement.setBigDecimal(1, id);
            statement.setString(2, orderNo);
            statement.setBigDecimal(3, amount);
            statement.setString(4, status);
            statement.setLong(5, createdAt);
            statement.setBigDecimal(6, id);
            statement.setString(7, orderNo);
            statement.setBigDecimal(8, amount);
            statement.setString(9, status);
            statement.setLong(10, createdAt);
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
