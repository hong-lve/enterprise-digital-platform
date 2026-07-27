package com.company.flinkjobs.cdcmirror;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

/**
 * Mirrors a test_orders_* CDC topic into Doris via its MySQL-wire-protocol
 * query port (same reasoning DataSourceConnectionService already uses
 * Doris "MYSQL" JDBC driver for querying) rather than the official
 * flink-doris-connector's Stream Load sink - a plain INSERT is enough here
 * because Doris's Unique Key table model does real upsert/delete at the
 * storage layer itself on primary key conflict, unlike ClickHouse/MySQL/
 * Oracle above which each need their own explicit upsert handling.
 */
public class DorisMirrorJob {
    public static void main(String[] args) throws Exception {
        String bootstrapServers = CdcMirrorSupport.arg(args, "kafka-bootstrap", "kafka:9092");
        String topic = CdcMirrorSupport.arg(args, "topic", "mysqldemo.cdc_demo.test_orders_mysql");
        String groupId = CdcMirrorSupport.arg(args, "group-id", "cdc-mirror-doris");
        String url = CdcMirrorSupport.arg(args, "sink-url", "jdbc:mysql://doris:9030/realtime_demo");
        String user = CdcMirrorSupport.arg(args, "sink-user", "root");
        String password = CdcMirrorSupport.arg(args, "sink-password", "");
        String table = CdcMirrorSupport.arg(args, "sink-table", "test_orders_mysql_doris_sink");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        CdcMirrorSupport.sourceRows(env, bootstrapServers, topic, groupId)
            .addSink(new DorisRowSink(url, user, password, table));
        env.execute("CDC Mirror -> Doris (" + table + ")");
    }

    private static class DorisRowSink extends RichSinkFunction<Map<String, String>> {
        private final String url;
        private final String user;
        private final String password;
        private final String table;
        private transient Connection connection;
        private transient PreparedStatement statement;

        DorisRowSink(String url, String user, String password, String table) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.table = table;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            // See ClickHouseMirrorJob's identical open() comment.
            Class.forName("com.mysql.cj.jdbc.Driver");
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
