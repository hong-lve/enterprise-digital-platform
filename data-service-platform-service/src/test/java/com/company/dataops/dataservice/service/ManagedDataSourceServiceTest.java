package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.company.dataops.dataservice.domain.DataSourceRecord;
import com.company.dataops.dataservice.repository.DataSourceRepository;
import com.company.dataops.dataservice.security.SecretCryptoService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedDataSourceServiceTest {
    private ManagedDataSourceService service;

    @BeforeEach
    void setUp() {
        service = new ManagedDataSourceService(
            mock(DataSourceRepository.class),
            mock(SecretCryptoService.class)
        );
    }

    @Test
    void buildsMySqlUrl() {
        assertEquals(
            "jdbc:mysql://mysql.internal:3306/orders?useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
            service.buildJdbcUrl(source("MYSQL", "mysql.internal", 3306, "orders"))
        );
    }

    @Test
    void buildsDorisUrlUsingMySqlProtocol() {
        assertEquals(
            "jdbc:mysql://doris-fe:9030/warehouse?useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
            service.buildJdbcUrl(source("DORIS", "doris-fe", 9030, "warehouse"))
        );
    }

    @Test
    void buildsOracleServiceUrl() {
        assertEquals(
            "jdbc:oracle:thin:@//oracle.internal:1521/ORDERPDB",
            service.buildJdbcUrl(source("ORACLE", "oracle.internal", 1521, "ORDERPDB"))
        );
    }

    @Test
    void buildsClickHouseUrl() {
        assertEquals(
            "jdbc:clickhouse://clickhouse.internal:8123/analytics",
            service.buildJdbcUrl(source("CLICKHOUSE", "clickhouse.internal", 8123, "analytics"))
        );
    }

    private DataSourceRecord source(String engine, String host, int port, String database) {
        Instant now = Instant.now();
        return new DataSourceRecord(
            1L, "source", engine, host, port, database, "reader", "cipher",
            0, 10, 10000L, 10, "DEV", "owner", "ACTIVE",
            "SUCCESS", null, now, now, now
        );
    }
}
