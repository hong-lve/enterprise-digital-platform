package com.company.dataops.console.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("migration_test")
        .withUsername("test")
        .withPassword("test");

    @Test
    void upgradesAnEmptyProductionSchemaThroughV42() throws Exception {
        Flyway flyway = Flyway.configure()
            .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
            .locations("classpath:db/migration")
            .load();

        assertEquals(42, flyway.migrate().migrationsExecuted);
        try (Connection connection = mysql.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement,
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='platform_lease'"));
            assertEquals(1, scalar(statement,
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='flink_stream_job' AND column_name='cluster_id'"));
        }
    }

    private int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
