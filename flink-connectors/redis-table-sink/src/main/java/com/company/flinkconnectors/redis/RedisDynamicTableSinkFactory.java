package com.company.flinkconnectors.redis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.UniqueConstraint;
import org.apache.flink.table.factories.DynamicTableSinkFactory;
import org.apache.flink.table.factories.FactoryUtil;

/**
 * 'connector'='redis' for SQL 流作业 sink DDL (see SinkTableDdlBuilder in
 * realtime-compute-service). Redis isn't JDBC-queryable, so unlike the
 * ClickHouse/MySQL/Oracle sinks (all teach flink-connector-jdbc a new
 * dialect) this is a standalone Table API connector written against Jedis.
 */
public class RedisDynamicTableSinkFactory implements DynamicTableSinkFactory {
    public static final String IDENTIFIER = "redis";

    public static final ConfigOption<String> HOST = ConfigOptions.key("host").stringType().noDefaultValue();
    public static final ConfigOption<Integer> PORT = ConfigOptions.key("port").intType().defaultValue(6379);
    public static final ConfigOption<String> PASSWORD = ConfigOptions.key("password").stringType().noDefaultValue();
    public static final ConfigOption<Integer> DATABASE = ConfigOptions.key("database").intType().defaultValue(0);
    public static final ConfigOption<String> KEY_PREFIX = ConfigOptions.key("key-prefix").stringType().defaultValue("");

    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        Set<ConfigOption<?>> options = new HashSet<>();
        options.add(HOST);
        return options;
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        Set<ConfigOption<?>> options = new HashSet<>();
        options.add(PORT);
        options.add(PASSWORD);
        options.add(DATABASE);
        options.add(KEY_PREFIX);
        return options;
    }

    @Override
    public org.apache.flink.table.connector.sink.DynamicTableSink createDynamicTableSink(Context context) {
        FactoryUtil.TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);
        helper.validate();
        ReadableConfig options = helper.getOptions();
        ResolvedSchema schema = context.getCatalogTable().getResolvedSchema();
        List<String> primaryKey = schema.getPrimaryKey()
            .map(UniqueConstraint::getColumns)
            .orElseThrow(() -> new ValidationException("Redis sink 需要在 CREATE TABLE 上声明 PRIMARY KEY，用来生成 Redis key"));
        return new RedisDynamicTableSink(
            options.get(HOST),
            options.get(PORT),
            options.get(PASSWORD),
            options.get(DATABASE),
            options.get(KEY_PREFIX),
            schema.getColumnNames(),
            primaryKey,
            schema.getColumnDataTypes().stream().map(dataType -> dataType.getLogicalType()).collect(Collectors.toList()));
    }
}
