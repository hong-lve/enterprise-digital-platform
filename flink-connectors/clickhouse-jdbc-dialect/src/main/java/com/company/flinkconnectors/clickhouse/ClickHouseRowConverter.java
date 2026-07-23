package com.company.flinkconnectors.clickhouse;

import org.apache.flink.connector.jdbc.converter.AbstractJdbcRowConverter;
import org.apache.flink.table.types.logical.RowType;

/** Standard scalar type conversion (BIGINT/VARCHAR/DECIMAL/TIMESTAMP/...) is all inherited - same shape as flink-connector-jdbc's own DerbyRowConverter. */
public class ClickHouseRowConverter extends AbstractJdbcRowConverter {
    private static final long serialVersionUID = 1L;

    public ClickHouseRowConverter(RowType rowType) {
        super(rowType);
    }

    @Override
    public String converterName() {
        return "ClickHouse";
    }
}
