package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.ApiSubscriptionRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ApiSubscriptionRepository {
    private static final String SELECT = """
        SELECT s.id, s.app_id, app.name AS app_name, app.app_key,
               s.api_id, api.name AS api_name, api.path AS api_path, api.method AS api_method,
               s.status, s.request_reason, s.qps_limit, s.daily_limit,
               COALESCE((
                 SELECT usage_row.request_count
                 FROM data_service_subscription_daily_usage usage_row
                 WHERE usage_row.subscription_id = s.id
                   AND usage_row.usage_date = CURRENT_DATE
               ), 0) AS daily_used,
               s.valid_from, s.valid_until, s.ip_allowlist_json,
               s.requested_by, s.requested_at, s.reviewed_by, s.reviewed_at,
               s.review_comment, s.created_at, s.updated_at
        FROM data_service_api_subscription s
        JOIN data_service_app app ON app.id = s.app_id
        JOIN data_service_api api ON api.id = s.api_id
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ApiSubscriptionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ApiSubscriptionRecord> findAll() {
        return jdbcTemplate.query(SELECT + " ORDER BY s.id DESC", this::map);
    }

    public Optional<ApiSubscriptionRecord> findById(long id) {
        return jdbcTemplate.query(SELECT + " WHERE s.id = ?", this::map, id).stream().findFirst();
    }

    public Optional<ApiSubscriptionRecord> findForRuntime(long appId, long apiId) {
        return jdbcTemplate.query(
            SELECT + " WHERE s.app_id = ? AND s.api_id = ?",
            this::map,
            appId,
            apiId
        ).stream().findFirst();
    }

    @Transactional
    public ApiSubscriptionRecord submit(
        long appId,
        long apiId,
        String reason,
        int qpsLimit,
        long dailyLimit,
        Instant validFrom,
        Instant validUntil,
        List<String> ipAllowlist,
        String actor
    ) {
        Optional<ApiSubscriptionRecord> existing = jdbcTemplate.query(
            SELECT + " WHERE s.app_id = ? AND s.api_id = ? FOR UPDATE",
            this::map,
            appId,
            apiId
        ).stream().findFirst();
        String allowlistJson = writeAllowlist(ipAllowlist);
        if (existing.isPresent()) {
            if ("PENDING".equals(existing.get().status()) || "APPROVED".equals(existing.get().status())) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This application already has an active or pending subscription"
                );
            }
            jdbcTemplate.update("""
                UPDATE data_service_api_subscription
                SET status = 'PENDING', request_reason = ?, qps_limit = ?, daily_limit = ?,
                    valid_from = ?, valid_until = ?, ip_allowlist_json = ?,
                    requested_by = ?, requested_at = CURRENT_TIMESTAMP,
                    reviewed_by = NULL, reviewed_at = NULL, review_comment = NULL
                WHERE id = ?
                """,
                reason, qpsLimit, dailyLimit, timestamp(validFrom), timestamp(validUntil),
                allowlistJson, actor, existing.get().id()
            );
            return findById(existing.get().id()).orElseThrow();
        }
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_api_subscription
                  (app_id, api_id, request_reason, qps_limit, daily_limit,
                   valid_from, valid_until, ip_allowlist_json, requested_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[]{"id"});
            statement.setLong(1, appId);
            statement.setLong(2, apiId);
            statement.setString(3, reason);
            statement.setInt(4, qpsLimit);
            statement.setLong(5, dailyLimit);
            statement.setTimestamp(6, timestamp(validFrom));
            statement.setTimestamp(7, timestamp(validUntil));
            statement.setString(8, allowlistJson);
            statement.setString(9, actor);
            return statement;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public void review(
        long id,
        String status,
        int qpsLimit,
        long dailyLimit,
        Instant validFrom,
        Instant validUntil,
        List<String> ipAllowlist,
        String actor,
        String comment
    ) {
        int affected = jdbcTemplate.update("""
            UPDATE data_service_api_subscription
            SET status = ?, qps_limit = ?, daily_limit = ?, valid_from = ?, valid_until = ?,
                ip_allowlist_json = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                review_comment = ?
            WHERE id = ? AND status = 'PENDING'
            """,
            status, qpsLimit, dailyLimit, timestamp(validFrom), timestamp(validUntil),
            writeAllowlist(ipAllowlist), actor, comment, id
        );
        if (affected != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subscription is no longer pending");
        }
    }

    public void suspend(long id, String actor, String comment) {
        int affected = jdbcTemplate.update("""
            UPDATE data_service_api_subscription
            SET status = 'SUSPENDED', reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                review_comment = ?
            WHERE id = ? AND status = 'APPROVED'
            """, actor, comment, id);
        if (affected != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only approved subscriptions can be suspended");
        }
    }

    private ApiSubscriptionRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ApiSubscriptionRecord(
            rs.getLong("id"),
            rs.getLong("app_id"),
            rs.getString("app_name"),
            rs.getString("app_key"),
            rs.getLong("api_id"),
            rs.getString("api_name"),
            rs.getString("api_path"),
            rs.getString("api_method"),
            rs.getString("status"),
            rs.getString("request_reason"),
            rs.getInt("qps_limit"),
            rs.getLong("daily_limit"),
            rs.getLong("daily_used"),
            instant(rs.getTimestamp("valid_from")),
            instant(rs.getTimestamp("valid_until")),
            readAllowlist(rs.getString("ip_allowlist_json")),
            rs.getString("requested_by"),
            instant(rs.getTimestamp("requested_at")),
            rs.getString("reviewed_by"),
            instant(rs.getTimestamp("reviewed_at")),
            rs.getString("review_comment"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at"))
        );
    }

    private String writeAllowlist(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize IP allowlist", exception);
        }
    }

    private List<String> readAllowlist(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read IP allowlist", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
