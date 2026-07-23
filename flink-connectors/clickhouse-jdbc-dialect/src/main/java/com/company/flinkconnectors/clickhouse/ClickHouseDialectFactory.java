package com.company.flinkconnectors.clickhouse;

import org.apache.flink.connector.jdbc.dialect.JdbcDialect;
import org.apache.flink.connector.jdbc.dialect.JdbcDialectFactory;

/** Registered via META-INF/services/org.apache.flink.connector.jdbc.dialect.JdbcDialectFactory. */
public class ClickHouseDialectFactory implements JdbcDialectFactory {
    @Override
    public boolean acceptsURL(String url) {
        return url.startsWith("jdbc:clickhouse:");
    }

    @Override
    public JdbcDialect create() {
        return new ClickHouseDialect();
    }
}
