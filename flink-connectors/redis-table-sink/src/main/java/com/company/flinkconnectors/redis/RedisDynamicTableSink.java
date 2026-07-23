package com.company.flinkconnectors.redis;

import java.util.List;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.SinkV2Provider;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.types.RowKind;

public class RedisDynamicTableSink implements DynamicTableSink {
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final String keyPrefix;
    private final List<String> columnNames;
    private final List<String> primaryKeyColumns;
    private final List<LogicalType> columnTypes;

    public RedisDynamicTableSink(String host, int port, String password, int database, String keyPrefix,
                                  List<String> columnNames, List<String> primaryKeyColumns, List<LogicalType> columnTypes) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.keyPrefix = keyPrefix;
        this.columnNames = columnNames;
        this.primaryKeyColumns = primaryKeyColumns;
        this.columnTypes = columnTypes;
    }

    @Override
    public ChangelogMode getChangelogMode(ChangelogMode requestedMode) {
        // A keyed upsert stream (mirrors a CDC changelog row) - UPDATE_BEFORE
        // is intentionally left out, Flink then drops those rows before they
        // ever reach the sink since HSET-by-primary-key doesn't need the old
        // value to perform an update.
        return ChangelogMode.newBuilder()
            .addContainedKind(RowKind.INSERT)
            .addContainedKind(RowKind.UPDATE_AFTER)
            .addContainedKind(RowKind.DELETE)
            .build();
    }

    @Override
    public SinkRuntimeProvider getSinkRuntimeProvider(Context context) {
        return SinkV2Provider.of(new RedisSink(host, port, password, database, keyPrefix, columnNames, primaryKeyColumns, columnTypes));
    }

    @Override
    public DynamicTableSink copy() {
        return new RedisDynamicTableSink(host, port, password, database, keyPrefix, columnNames, primaryKeyColumns, columnTypes);
    }

    @Override
    public String asSummaryString() {
        return "Redis";
    }
}
