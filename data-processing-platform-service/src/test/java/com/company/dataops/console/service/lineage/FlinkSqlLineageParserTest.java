package com.company.dataops.console.service.lineage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlinkSqlLineageParserTest {
    @Test
    void parsesEveryInsertStatement() {
        String sql = """
            CREATE TABLE source_table (`id` BIGINT) WITH ('connector'='kafka', 'topic'='orders');
            CREATE TABLE sink_a (`id` BIGINT) WITH ('connector'='jdbc', 'table-name'='a');
            CREATE TABLE sink_b (`id` BIGINT) WITH ('connector'='jdbc', 'table-name'='b');
            INSERT INTO sink_a SELECT id FROM source_table;
            INSERT INTO sink_b SELECT id FROM source_table;
            """;

        FlinkSqlLineageParser.SqlLineageResult result = new FlinkSqlLineageParser().parse(sql);

        assertEquals(2, result.inserts().size());
        assertEquals("sink_a", result.inserts().get(0).targetTable());
        assertEquals("sink_b", result.inserts().get(1).targetTable());
        assertEquals(2, result.columnLineages().size());
    }

    @Test
    void mergesColumnSourcesAcrossUnionBranches() {
        String sql = """
            CREATE TABLE source_a (`id` BIGINT) WITH ('connector'='kafka', 'topic'='a');
            CREATE TABLE source_b (`id` BIGINT) WITH ('connector'='kafka', 'topic'='b');
            CREATE TABLE sink_table (`id` BIGINT) WITH ('connector'='jdbc', 'table-name'='sink');
            INSERT INTO sink_table SELECT id FROM source_a UNION ALL SELECT id FROM source_b;
            """;

        FlinkSqlLineageParser.SqlLineageResult result = new FlinkSqlLineageParser().parse(sql);

        assertEquals(1, result.inserts().size());
        assertEquals(2, result.columnLineages().get(0).sourceColumns().size());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }
}
