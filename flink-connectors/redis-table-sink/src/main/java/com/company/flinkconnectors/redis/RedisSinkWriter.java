package com.company.flinkconnectors.redis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.types.RowKind;
import redis.clients.jedis.Jedis;

/**
 * One Redis key per sink row (key = key-prefix + primary key column
 * value(s), ':'-joined for a composite key), written via HSET with one hash
 * field per table column - lets 实时数据查询's Redis command box read a row
 * straight back with HGETALL. DELETE-kind rows issue a plain DEL.
 */
public class RedisSinkWriter implements SinkWriter<RowData> {
    private final Jedis jedis;
    private final List<String> columnNames;
    private final RowData.FieldGetter[] fieldGetters;
    private final int[] primaryKeyIndexes;
    private final String keyPrefix;

    public RedisSinkWriter(String host, int port, String password, int database, String keyPrefix,
                            List<String> columnNames, List<String> primaryKeyColumns, List<LogicalType> columnTypes) {
        this.jedis = new Jedis(host, port);
        if (password != null && !password.isBlank()) {
            jedis.auth(password);
        }
        if (database != 0) {
            jedis.select(database);
        }
        this.columnNames = columnNames;
        this.keyPrefix = keyPrefix;
        this.fieldGetters = new RowData.FieldGetter[columnTypes.size()];
        for (int i = 0; i < columnTypes.size(); i++) {
            fieldGetters[i] = RowData.createFieldGetter(columnTypes.get(i), i);
        }
        this.primaryKeyIndexes = new int[primaryKeyColumns.size()];
        for (int i = 0; i < primaryKeyColumns.size(); i++) {
            primaryKeyIndexes[i] = columnNames.indexOf(primaryKeyColumns.get(i));
        }
    }

    @Override
    public void write(RowData row, Context context) {
        String key = buildKey(row);
        if (row.getRowKind() == RowKind.DELETE) {
            jedis.del(key);
            return;
        }
        Map<String, String> hash = new HashMap<>();
        for (int i = 0; i < columnNames.size(); i++) {
            Object value = fieldGetters[i].getFieldOrNull(row);
            hash.put(columnNames.get(i), value == null ? "" : value.toString());
        }
        jedis.hset(key, hash);
    }

    private String buildKey(RowData row) {
        StringBuilder key = new StringBuilder(keyPrefix);
        for (int i = 0; i < primaryKeyIndexes.length; i++) {
            if (i > 0) {
                key.append(':');
            }
            Object value = fieldGetters[primaryKeyIndexes[i]].getFieldOrNull(row);
            key.append(value == null ? "" : value.toString());
        }
        return key.toString();
    }

    @Override
    public void flush(boolean endOfInput) {
    }

    @Override
    public void close() {
        jedis.close();
    }
}
