package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.DataSourceRecord;
import com.company.dataops.dataservice.repository.DataSourceRepository;
import com.company.dataops.dataservice.security.SecretCryptoService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManagedDataSourceService {
    private static final Map<String, String> DRIVER_CLASSES = Map.of(
        "MYSQL", "com.mysql.cj.jdbc.Driver",
        "DORIS", "com.mysql.cj.jdbc.Driver",
        "ORACLE", "oracle.jdbc.OracleDriver",
        "CLICKHOUSE", "com.clickhouse.jdbc.ClickHouseDriver"
    );

    private final DataSourceRepository repository;
    private final SecretCryptoService cryptoService;
    private final Map<Long, PoolHolder> pools = new ConcurrentHashMap<>();

    public ManagedDataSourceService(DataSourceRepository repository, SecretCryptoService cryptoService) {
        this.repository = repository;
        this.cryptoService = cryptoService;
    }

    public String buildJdbcUrl(DataSourceRecord source) {
        String database = source.databaseName();
        return switch (source.engineType().toUpperCase(Locale.ROOT)) {
            case "MYSQL", "DORIS" -> "jdbc:mysql://" + source.host() + ":" + source.port() + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
            case "ORACLE" -> "jdbc:oracle:thin:@//" + source.host() + ":" + source.port() + "/" + database;
            case "CLICKHOUSE" -> "jdbc:clickhouse://" + source.host() + ":" + source.port() + "/" + database;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的数据源类型：" + source.engineType());
        };
    }

    public DataSourceRecord test(long id) {
        DataSourceRecord source = require(id);
        String probeSql = "ORACLE".equals(source.engineType()) ? "SELECT 1 FROM DUAL" : "SELECT 1";
        try (HikariDataSource probe = createPool(source, 1, 0);
             Connection connection = probe.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(source.queryTimeoutSeconds(), 1));
            statement.execute(probeSql);
            return repository.updateTestResult(id, true, "连接成功");
        } catch (Exception exception) {
            String message = rootMessage(exception);
            repository.updateTestResult(id, false, message);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "连接失败：" + message);
        }
    }

    public NamedParameterJdbcTemplate queryTemplate(long id) {
        DataSourceRecord source = require(id);
        if (!"ACTIVE".equals(source.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "数据源未启用：" + source.name());
        }
        PoolHolder holder = pools.compute(id, (key, current) -> {
            if (current != null && current.version().equals(source.updatedAt())) {
                return current;
            }
            if (current != null) {
                current.pool().close();
            }
            HikariDataSource pool = createPool(source, source.poolMaxSize(), source.poolMinIdle());
            JdbcTemplate jdbcTemplate = new JdbcTemplate(pool);
            jdbcTemplate.setQueryTimeout(source.queryTimeoutSeconds());
            return new PoolHolder(source.updatedAt(), pool, new NamedParameterJdbcTemplate(jdbcTemplate));
        });
        return holder.template();
    }

    public DataSourceRecord require(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据源不存在"));
    }

    public void evict(long id) {
        PoolHolder holder = pools.remove(id);
        if (holder != null) {
            holder.pool().close();
        }
    }

    @PreDestroy
    public void close() {
        pools.values().forEach(holder -> holder.pool().close());
        pools.clear();
    }

    private HikariDataSource createPool(DataSourceRecord source, int maxPoolSize, int minIdle) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("data-service-" + source.id() + "-" + source.engineType().toLowerCase(Locale.ROOT));
        config.setDriverClassName(DRIVER_CLASSES.get(source.engineType().toUpperCase(Locale.ROOT)));
        config.setJdbcUrl(buildJdbcUrl(source));
        config.setUsername(source.username());
        config.setPassword(cryptoService.decrypt(source.passwordCiphertext()));
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(source.connectionTimeoutMs());
        config.setValidationTimeout(Math.min(source.connectionTimeoutMs(), 5000));
        config.setInitializationFailTimeout(source.connectionTimeoutMs());
        config.setReadOnly(true);
        return new HikariDataSource(config);
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = cursor.getClass().getSimpleName();
        }
        return message.length() > 450 ? message.substring(0, 450) : message;
    }

    private record PoolHolder(
        Instant version,
        HikariDataSource pool,
        NamedParameterJdbcTemplate template
    ) {
    }
}
