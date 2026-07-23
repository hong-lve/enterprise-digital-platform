package com.company.flinkconnectors.redis;

import java.util.List;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.LogicalType;

public class RedisSink implements Sink<RowData> {
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final String keyPrefix;
    private final List<String> columnNames;
    private final List<String> primaryKeyColumns;
    private final List<LogicalType> columnTypes;

    public RedisSink(String host, int port, String password, int database, String keyPrefix,
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
    public SinkWriter<RowData> createWriter(InitContext context) {
        return new RedisSinkWriter(host, port, password, database, keyPrefix, columnNames, primaryKeyColumns, columnTypes);
    }
}
