package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.ChangeRequestRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ChangeApprovalRepository {
    private final JdbcTemplate jdbcTemplate;

    public ChangeApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ChangeRequestRecord> findAll(int limit) {
        return jdbcTemplate.query("""
            SELECT id, action_type, target_type, target_id, target_summary, environment,
                   payload_json, requester, status, approver, decision_comment,
                   decided_at, created_at, updated_at
            FROM data_service_change_request
            ORDER BY id DESC
            LIMIT ?
            """, this::map, limit);
    }

    public Optional<ChangeRequestRecord> findById(long id) {
        return jdbcTemplate.query("""
            SELECT id, action_type, target_type, target_id, target_summary, environment,
                   payload_json, requester, status, approver, decision_comment,
                   decided_at, created_at, updated_at
            FROM data_service_change_request
            WHERE id = ?
            """, this::map, id).stream().findFirst();
    }

    public ChangeRequestRecord create(
        String actionType,
        String targetType,
        long targetId,
        String targetSummary,
        String environment,
        String payloadJson,
        String requester
    ) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                    INSERT INTO data_service_change_request
                      (action_type, target_type, target_id, target_summary,
                       environment, payload_json, requester)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
                statement.setString(1, actionType);
                statement.setString(2, targetType);
                statement.setLong(3, targetId);
                statement.setString(4, targetSummary);
                statement.setString(5, environment);
                statement.setString(6, payloadJson);
                statement.setString(7, requester);
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A pending change already exists for this resource"
            );
        }
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public void decide(long id, String status, String approver, String comment) {
        int affected = jdbcTemplate.update("""
            UPDATE data_service_change_request
            SET status = ?, approver = ?, decision_comment = ?, decided_at = CURRENT_TIMESTAMP,
                active_flag = NULL
            WHERE id = ? AND status = 'PENDING'
            """, status, approver, comment, id);
        if (affected != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Change request is no longer pending");
        }
    }

    private ChangeRequestRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChangeRequestRecord(
            rs.getLong("id"),
            rs.getString("action_type"),
            rs.getString("target_type"),
            rs.getLong("target_id"),
            rs.getString("target_summary"),
            rs.getString("environment"),
            rs.getString("payload_json"),
            rs.getString("requester"),
            rs.getString("status"),
            rs.getString("approver"),
            rs.getString("decision_comment"),
            toInstant(rs.getTimestamp("decided_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
