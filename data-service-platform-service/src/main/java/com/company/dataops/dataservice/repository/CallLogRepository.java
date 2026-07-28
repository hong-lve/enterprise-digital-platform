package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.CallLogRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CallLogRepository {
    private final JdbcTemplate jdbcTemplate;

    public CallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
        Long apiId,
        String requestId,
        String appKey,
        String path,
        String method,
        int statusCode,
        long elapsedMs,
        Integer rowCount,
        boolean testCall,
        String clientIp,
        String errorMessage
    ) {
        save(
            apiId, requestId, null, appKey, path, method, statusCode, elapsedMs,
            rowCount, testCall, clientIp, errorMessage
        );
    }

    public void save(
        Long apiId,
        String requestId,
        String traceId,
        String appKey,
        String path,
        String method,
        int statusCode,
        long elapsedMs,
        Integer rowCount,
        boolean testCall,
        String clientIp,
        String errorMessage
    ) {
        jdbcTemplate.update("""
            INSERT INTO data_service_call_log
                (api_id, request_id, trace_id, app_key, api_path, method, status_code,
                 elapsed_ms, row_count, test_call, client_ip, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            apiId, requestId, traceId, appKey, path, method, statusCode, elapsedMs,
            rowCount, testCall, clientIp, truncate(errorMessage)
        );
    }

    public List<CallLogRecord> findRecent(int limit) {
        return jdbcTemplate.query("""
            SELECT id, api_id, request_id, trace_id, app_key, api_path, method, status_code, elapsed_ms,
                   row_count, test_call, client_ip, error_message, occurred_at
            FROM data_service_call_log
            ORDER BY id DESC
            LIMIT ?
            """, this::map, limit);
    }

    private CallLogRecord map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp occurredAt = rs.getTimestamp("occurred_at");
        return new CallLogRecord(
            rs.getLong("id"),
            (Long) rs.getObject("api_id"),
            rs.getString("request_id"),
            rs.getString("trace_id"),
            rs.getString("app_key"),
            rs.getString("api_path"),
            rs.getString("method"),
            rs.getInt("status_code"),
            rs.getLong("elapsed_ms"),
            (Integer) rs.getObject("row_count"),
            rs.getBoolean("test_call"),
            rs.getString("client_ip"),
            rs.getString("error_message"),
            occurredAt == null ? null : occurredAt.toInstant()
        );
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }
}
