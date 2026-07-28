package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.DataSourceRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class DataSourceRepository {
    private static final String COLUMNS = """
        id, name, engine_type, host, port, database_name, username, password_ciphertext,
        pool_min_idle, pool_max_size, connection_timeout_ms, query_timeout_seconds,
        environment, owner, status, last_test_status, last_test_message, last_test_at,
        created_at, updated_at
        """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<DataSourceRecord> rowMapper = this::map;

    public DataSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DataSourceRecord> findAll() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM data_service_connection ORDER BY id DESC", rowMapper);
    }

    public Optional<DataSourceRecord> findById(long id) {
        return jdbcTemplate.query(
            "SELECT " + COLUMNS + " FROM data_service_connection WHERE id = ?",
            rowMapper,
            id
        ).stream().findFirst();
    }

    public DataSourceRecord create(DataSourceRecord value) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_connection
                    (name, engine_type, host, port, database_name, username, password_ciphertext,
                     pool_min_idle, pool_max_size, connection_timeout_ms, query_timeout_seconds,
                     environment, owner, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                """, new String[]{"id"});
            bind(statement, value, false);
            return statement;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public DataSourceRecord update(long id, DataSourceRecord value, String encryptedPassword) {
        if (encryptedPassword == null) {
            jdbcTemplate.update("""
                UPDATE data_service_connection
                SET name = ?, engine_type = ?, host = ?, port = ?, database_name = ?, username = ?,
                    pool_min_idle = ?, pool_max_size = ?, connection_timeout_ms = ?,
                    query_timeout_seconds = ?, environment = ?, owner = ?,
                    status = 'DRAFT', last_test_status = NULL, last_test_message = NULL, last_test_at = NULL
                WHERE id = ?
                """,
                value.name(), value.engineType(), value.host(), value.port(), value.databaseName(), value.username(),
                value.poolMinIdle(), value.poolMaxSize(), value.connectionTimeoutMs(), value.queryTimeoutSeconds(),
                value.environment(), value.owner(), id
            );
        } else {
            jdbcTemplate.update("""
                UPDATE data_service_connection
                SET name = ?, engine_type = ?, host = ?, port = ?, database_name = ?, username = ?,
                    password_ciphertext = ?, pool_min_idle = ?, pool_max_size = ?, connection_timeout_ms = ?,
                    query_timeout_seconds = ?, environment = ?, owner = ?,
                    status = 'DRAFT', last_test_status = NULL, last_test_message = NULL, last_test_at = NULL
                WHERE id = ?
                """,
                value.name(), value.engineType(), value.host(), value.port(), value.databaseName(), value.username(),
                encryptedPassword, value.poolMinIdle(), value.poolMaxSize(), value.connectionTimeoutMs(),
                value.queryTimeoutSeconds(), value.environment(), value.owner(), id
            );
        }
        return findById(id).orElseThrow();
    }

    public DataSourceRecord updateStatus(long id, String status) {
        jdbcTemplate.update("UPDATE data_service_connection SET status = ? WHERE id = ?", status, id);
        return findById(id).orElseThrow();
    }

    public DataSourceRecord updateTestResult(long id, boolean success, String message) {
        jdbcTemplate.update("""
            UPDATE data_service_connection
            SET last_test_status = ?, last_test_message = ?, last_test_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, success ? "SUCCESS" : "FAILED", message, id);
        return findById(id).orElseThrow();
    }

    private void bind(java.sql.PreparedStatement statement, DataSourceRecord value, boolean includeId)
        throws SQLException {
        statement.setString(1, value.name());
        statement.setString(2, value.engineType());
        statement.setString(3, value.host());
        statement.setInt(4, value.port());
        statement.setString(5, value.databaseName());
        statement.setString(6, value.username());
        statement.setString(7, value.passwordCiphertext());
        statement.setInt(8, value.poolMinIdle());
        statement.setInt(9, value.poolMaxSize());
        statement.setLong(10, value.connectionTimeoutMs());
        statement.setInt(11, value.queryTimeoutSeconds());
        statement.setString(12, value.environment());
        statement.setString(13, value.owner());
        if (includeId) {
            statement.setLong(14, value.id());
        }
    }

    private DataSourceRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new DataSourceRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("engine_type"),
            rs.getString("host"),
            rs.getInt("port"),
            rs.getString("database_name"),
            rs.getString("username"),
            rs.getString("password_ciphertext"),
            rs.getInt("pool_min_idle"),
            rs.getInt("pool_max_size"),
            rs.getLong("connection_timeout_ms"),
            rs.getInt("query_timeout_seconds"),
            rs.getString("environment"),
            rs.getString("owner"),
            rs.getString("status"),
            rs.getString("last_test_status"),
            rs.getString("last_test_message"),
            toInstant(rs.getTimestamp("last_test_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
