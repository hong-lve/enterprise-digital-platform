package com.company.dataops.console.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.entity.ReconciliationCheckEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.mapper.ReconciliationCheckMapper;
import com.company.dataops.console.service.datasource.DataSourceConnectionService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tier 3 item 5 of the reliability roadmap ("Testcontainers/Flink-MiniCluster/
 * Kafka-CDC E2E test scaffolding") - the first real-infrastructure test in
 * this module. DataReconciliationService is a natural first target: it does
 * its actual comparison work over live JDBC connections
 * (DataSourceConnectionService.query()), so a mock can only ever assert "the
 * right SQL string was built," never "the right SQL string actually finds
 * real drift against a real database" - which is the entire point of a
 * reconciliation job.
 *
 * Two real, independent MySQL containers stand in for "the CDC source" and
 * "wherever it got mirrored to" - genuinely separate connections, exactly
 * like a real source/target pair, not two schemas on one shared instance.
 *
 * Runs via `mvn verify` (maven-failsafe-plugin), not the fast `mvn test`
 * unit suite - see pom.xml's failsafe binding and the *IT.java naming
 * convention it picks up.
 */
@Testcontainers(disabledWithoutDocker = true)
class DataReconciliationServiceIT {
    @Container
    static MySQLContainer<?> sourceDb = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("source_db").withUsername("test").withPassword("test");

    @Container
    static MySQLContainer<?> targetDb = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("target_db").withUsername("test").withPassword("test");

    private DataReconciliationService service;
    private DataSourceMapper dataSourceMapper;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceMapper = mock(DataSourceMapper.class);
        service = new DataReconciliationService(
            mock(ReconciliationCheckMapper.class),
            dataSourceMapper,
            new DataSourceConnectionService(dataSourceMapper),
            mock(RealtimeAlertService.class));

        DataSourceEntity source = mysqlDataSource(1L, sourceDb);
        DataSourceEntity target = mysqlDataSource(2L, targetDb);
        when(dataSourceMapper.selectById(eq(1L))).thenReturn(source);
        when(dataSourceMapper.selectById(eq(2L))).thenReturn(target);

        seedOrdersTable(sourceDb, 5);
        seedOrdersTable(targetDb, 5); // matching by default - individual tests corrupt one side
    }

    @Test
    void rowCountCheckPassesWhenBothSidesGenuinelyMatch() {
        ReconciliationCheckEntity result = service.runCheck(rowCountCheck());
        assertEquals("OK", result.getLastState());
        assertNull(result.getLastError());
    }

    @Test
    void rowCountCheckDetectsARealDroppedSinkWrite() throws Exception {
        // Simulates the exact failure mode DataReconciliationService exists to
        // catch: a sink write that silently failed, leaving the target one
        // row short - the connector itself would still report healthy.
        try (Connection connection = DriverManager.getConnection(targetDb.getJdbcUrl(), targetDb.getUsername(), targetDb.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM orders WHERE id = 1");
        }
        ReconciliationCheckEntity result = service.runCheck(rowCountCheck());
        assertEquals("DRIFT", result.getLastState());
    }

    @Test
    void aggregateCheckCatchesValueCorruptionRowCountAloneWouldMiss() throws Exception {
        // Same row count on both sides - a ROW_COUNT check would report OK -
        // but one row's value got corrupted in transit (a lossy type
        // conversion, a botched sink transform). Only AGGREGATE catches this.
        try (Connection connection = DriverManager.getConnection(targetDb.getJdbcUrl(), targetDb.getUsername(), targetDb.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE orders SET amount = 1 WHERE id = 1");
        }
        ReconciliationCheckEntity rowCountResult = service.runCheck(rowCountCheck());
        assertEquals("OK", rowCountResult.getLastState());

        ReconciliationCheckEntity aggregateResult = service.runCheck(aggregateCheck());
        assertEquals("DRIFT", aggregateResult.getLastState());
    }

    private DataSourceEntity mysqlDataSource(Long id, MySQLContainer<?> container) {
        DataSourceEntity entity = new DataSourceEntity();
        entity.setId(id);
        entity.setType("MYSQL");
        entity.setHost(container.getHost());
        entity.setPort(container.getMappedPort(3306));
        entity.setUsername(container.getUsername());
        entity.setPassword(container.getPassword());
        entity.setDatabaseName(container.getDatabaseName());
        return entity;
    }

    private void seedOrdersTable(MySQLContainer<?> container, int rowCount) throws Exception {
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS orders (id BIGINT PRIMARY KEY, amount DECIMAL(10,2) NOT NULL)");
            statement.execute("DELETE FROM orders");
            for (int i = 1; i <= rowCount; i++) {
                statement.execute("INSERT INTO orders (id, amount) VALUES (" + i + ", 100.00)");
            }
        }
    }

    private ReconciliationCheckEntity rowCountCheck() {
        ReconciliationCheckEntity check = new ReconciliationCheckEntity();
        check.setId(1L);
        check.setName("IT-row-count");
        check.setCheckType("ROW_COUNT");
        check.setSourceDataSourceId(1L);
        check.setTargetDataSourceId(2L);
        check.setSourceDatabase(sourceDb.getDatabaseName());
        check.setTargetDatabase(targetDb.getDatabaseName());
        check.setSourceTable("orders");
        check.setTargetTable("orders");
        check.setTolerance(0);
        return check;
    }

    private ReconciliationCheckEntity aggregateCheck() {
        ReconciliationCheckEntity check = rowCountCheck();
        check.setId(2L);
        check.setName("IT-aggregate");
        check.setCheckType("AGGREGATE");
        check.setAggregateColumn("amount");
        return check;
    }
}
