package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.DatasetRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class DatasetRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<DatasetRecord> rowMapper = this::map;

    public DatasetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DatasetRecord> findAll() {
        return jdbcTemplate.query("""
            SELECT id, name, description, source_type, source_name, connection_mode, connection_id,
                   table_name, owner, status, created_at, updated_at
            FROM data_service_dataset
            ORDER BY id DESC
            """, rowMapper);
    }

    public Optional<DatasetRecord> findById(long id) {
        return jdbcTemplate.query("""
            SELECT id, name, description, source_type, source_name, connection_mode, connection_id,
                   table_name, owner, status, created_at, updated_at
            FROM data_service_dataset
            WHERE id = ?
            """, rowMapper, id).stream().findFirst();
    }

    public DatasetRecord create(
        String name,
        String description,
        String sourceType,
        String sourceName,
        String connectionMode,
        Long connectionId,
        String tableName,
        String owner
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_dataset
                    (name, description, source_type, source_name, connection_mode, connection_id, table_name, owner, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, new String[]{"id"});
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setString(3, sourceType);
            statement.setString(4, sourceName);
            statement.setString(5, connectionMode);
            if (connectionId == null) {
                statement.setNull(6, java.sql.Types.BIGINT);
            } else {
                statement.setLong(6, connectionId);
            }
            statement.setString(7, tableName);
            statement.setString(8, owner);
            return statement;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    private DatasetRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new DatasetRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("source_type"),
            rs.getString("source_name"),
            rs.getString("connection_mode"),
            rs.getObject("connection_id", Long.class),
            rs.getString("table_name"),
            rs.getString("owner"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
