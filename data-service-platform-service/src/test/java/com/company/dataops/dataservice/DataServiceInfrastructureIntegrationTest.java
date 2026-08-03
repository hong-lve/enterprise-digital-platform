package com.company.dataops.dataservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.dataops.dataservice.repository.RequestSecurityRepository;
import com.company.dataops.dataservice.service.DistributedCircuitBreakerStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "platform.data-service.admin.bootstrap.enabled=false",
    "management.tracing.enabled=false"
})
class DataServiceInfrastructureIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("data_service_db")
        .withUsername("data_service")
        .withPassword("data_service");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    RequestSecurityRepository securityRepository;

    @Autowired
    DistributedCircuitBreakerStore circuitStore;

    @Test
    void appliesAllMigrationsAndUsesRedisRateLimiter() {
        Integer migrations = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
            Integer.class
        );
        assertEquals(17, migrations);
        assertTrue(securityRepository.registerNonce("integration-app", "nonce_1234567890123456", Instant.now().plusSeconds(30)));
        assertFalse(securityRepository.registerNonce("integration-app", "nonce_1234567890123456", Instant.now().plusSeconds(30)));
        assertTrue(securityRepository.acquire("integration-app", 2, 100L).allowed());
        assertTrue(securityRepository.acquire("integration-app", 2, 100L).allowed());
        assertFalse(securityRepository.acquire("integration-app", 2, 100L).allowed());
    }

    @Test
    void sharesCircuitStateAndAllowsOnlyOneHalfOpenProbe() throws Exception {
        long apiId = 991L;
        Duration openDuration = Duration.ofMillis(100);
        circuitStore.failure(apiId, 2, openDuration);
        circuitStore.failure(apiId, 2, openDuration);
        assertEquals(DistributedCircuitBreakerStore.Permit.REJECT, circuitStore.acquire(apiId, openDuration));

        Thread.sleep(150);
        assertEquals(DistributedCircuitBreakerStore.Permit.PROBE, circuitStore.acquire(apiId, openDuration));
        assertEquals(DistributedCircuitBreakerStore.Permit.REJECT, circuitStore.acquire(apiId, openDuration));
        circuitStore.success(apiId);
        assertEquals(DistributedCircuitBreakerStore.Permit.ALLOW, circuitStore.acquire(apiId, openDuration));
    }
}
