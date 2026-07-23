package com.company.flinkconnectors.clickhouse;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.apache.flink.connector.jdbc.converter.JdbcRowConverter;
import org.apache.flink.connector.jdbc.dialect.AbstractDialect;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.RowType;

/**
 * JDBC dialect for ClickHouse - modeled directly on flink-connector-jdbc's
 * own DerbyDialect (the simplest built-in dialect). ClickHouse has no real
 * UPSERT/MERGE, but unlike returning Optional.empty() (Derby's approach),
 * getUpsertStatement() here reuses a plain INSERT: a PRIMARY KEY on the sink
 * table is only ever declared to satisfy Flink's changelog-mode validation
 * (see SinkTableDdlBuilder's comment), not because ClickHouse can really
 * upsert - but JdbcDynamicTableSink still selects its keyed/buffered
 * execution path purely because a PRIMARY KEY is present, independent of
 * what this dialect returns. When getUpsertStatement() was empty, that path
 * tried to build a separate real UPDATE statement (which this dialect never
 * implements) and crashed with an ArrayIndexOutOfBoundsException at
 * checkpoint-time flush. Handing back the INSERT statement instead matches
 * how this project already writes to ClickHouse elsewhere (ReplacingMergeTree
 * + updated_at version column + FINAL reads for idempotency at the storage
 * layer, not via upsert) - ClickHouse just accumulates duplicate-key rows for
 * a later background merge rather than rejecting them.
 */
public class ClickHouseDialect extends AbstractDialect {
    private static final long serialVersionUID = 1L;

    // ClickHouse's Decimal(P, S) supports P up to 76, but Flink's own
    // DECIMAL type caps precision at 38 (its DecimalType max) - matching
    // Flink's own ceiling here rather than ClickHouse's is what actually
    // matters, same reasoning CdcTableSchemaService's DECIMAL mapping
    // already uses.
    private static final int MAX_DECIMAL_PRECISION = 38;
    private static final int MIN_DECIMAL_PRECISION = 1;

    // ClickHouse's DateTime64 supports 0-9 fractional digits, matching
    // Flink's own TIMESTAMP precision range.
    private static final int MAX_TIMESTAMP_PRECISION = 9;
    private static final int MIN_TIMESTAMP_PRECISION = 0;

    @Override
    public Optional<Range> decimalPrecisionRange() {
        return Optional.of(Range.of(MIN_DECIMAL_PRECISION, MAX_DECIMAL_PRECISION));
    }

    @Override
    public Optional<Range> timestampPrecisionRange() {
        return Optional.of(Range.of(MIN_TIMESTAMP_PRECISION, MAX_TIMESTAMP_PRECISION));
    }

    @Override
    public JdbcRowConverter getRowConverter(RowType rowType) {
        return new ClickHouseRowConverter(rowType);
    }

    @Override
    public Optional<String> defaultDriverName() {
        return Optional.of("com.clickhouse.jdbc.ClickHouseDriver");
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public String dialectName() {
        return "ClickHouse";
    }

    @Override
    public String getLimitClause(long limit) {
        return "LIMIT " + limit;
    }

    @Override
    public Optional<String> getUpsertStatement(String tableName, String[] fieldNames, String[] uniqueKeyFields) {
        return Optional.of(getInsertIntoStatement(tableName, fieldNames));
    }

    @Override
    public Set<LogicalTypeRoot> supportedTypes() {
        // Matches the scalar type set CdcTableSchemaService's MySQL->Flink
        // mapping already produces (services/realtime-compute-service/.../
        // service/kafka/CdcTableSchemaService.java) - no ClickHouse-specific
        // types (Array/Map/LowCardinality) in this first version.
        return EnumSet.of(
            LogicalTypeRoot.CHAR,
            LogicalTypeRoot.VARCHAR,
            LogicalTypeRoot.BOOLEAN,
            LogicalTypeRoot.VARBINARY,
            LogicalTypeRoot.DECIMAL,
            LogicalTypeRoot.TINYINT,
            LogicalTypeRoot.SMALLINT,
            LogicalTypeRoot.INTEGER,
            LogicalTypeRoot.BIGINT,
            LogicalTypeRoot.FLOAT,
            LogicalTypeRoot.DOUBLE,
            LogicalTypeRoot.DATE,
            LogicalTypeRoot.TIME_WITHOUT_TIME_ZONE,
            LogicalTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE);
    }
}
