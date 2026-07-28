package com.company.dataops.dataservice.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RequestSecurityRepository {
    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong cleanupCounter = new AtomicLong();

    public RequestSecurityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean registerNonce(String appKey, String nonce, Instant expiresAt) {
        try {
            jdbcTemplate.update("""
                INSERT INTO data_service_request_nonce (app_key, nonce, expires_at)
                VALUES (?, ?, ?)
                """, appKey, nonce, java.sql.Timestamp.from(expiresAt));
            cleanupOccasionally();
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Transactional
    public RateLimitDecision acquire(String appKey, int limit, long windowSecond) {
        jdbcTemplate.update("""
            INSERT INTO data_service_rate_limit_counter (app_key, window_second, request_count)
            VALUES (?, ?, 1)
            ON DUPLICATE KEY UPDATE request_count = request_count + 1
            """, appKey, windowSecond);
        Integer count = jdbcTemplate.queryForObject("""
            SELECT request_count
            FROM data_service_rate_limit_counter
            WHERE app_key = ? AND window_second = ?
            """, Integer.class, appKey, windowSecond);
        int current = count == null ? 1 : count;
        cleanupOccasionally();
        return new RateLimitDecision(current <= limit, limit, Math.max(0, limit - current));
    }

    @Transactional
    public DailyLimitDecision acquireDaily(long subscriptionId, long limit, LocalDate usageDate) {
        jdbcTemplate.update("""
            INSERT INTO data_service_subscription_daily_usage
              (subscription_id, usage_date, request_count)
            VALUES (?, ?, 1)
            ON DUPLICATE KEY UPDATE request_count = request_count + 1
            """, subscriptionId, java.sql.Date.valueOf(usageDate));
        Long count = jdbcTemplate.queryForObject("""
            SELECT request_count
            FROM data_service_subscription_daily_usage
            WHERE subscription_id = ? AND usage_date = ?
            """, Long.class, subscriptionId, java.sql.Date.valueOf(usageDate));
        long current = count == null ? 1 : count;
        return new DailyLimitDecision(
            current <= limit,
            limit,
            Math.max(0, limit - current),
            current
        );
    }

    private void cleanupOccasionally() {
        if (cleanupCounter.incrementAndGet() % 1000 != 0) {
            return;
        }
        jdbcTemplate.update(
            "DELETE FROM data_service_request_nonce WHERE expires_at < ?",
            java.sql.Timestamp.from(Instant.now())
        );
        jdbcTemplate.update(
            "DELETE FROM data_service_rate_limit_counter WHERE window_second < ?",
            Instant.now().minus(10, ChronoUnit.MINUTES).getEpochSecond()
        );
        jdbcTemplate.update("""
            DELETE FROM data_service_subscription_daily_usage
            WHERE usage_date < ?
            """, java.sql.Date.valueOf(LocalDate.now().minusDays(7)));
    }

    public record RateLimitDecision(boolean allowed, int limit, int remaining) {
    }

    public record DailyLimitDecision(boolean allowed, long limit, long remaining, long current) {
    }
}
