package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.AlertEventRecord;
import com.company.dataops.dataservice.domain.SloRuleRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SloRepository {
    private final JdbcTemplate jdbcTemplate;

    public SloRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SloRuleRecord> findRules() {
        return jdbcTemplate.query("""
            SELECT id, api_id, name, enabled, window_minutes, min_requests,
                   min_success_rate, max_p95_ms, created_by, created_at, updated_at
            FROM data_service_slo_rule
            ORDER BY id DESC
            """, this::mapRule);
    }

    public Optional<SloRuleRecord> findRule(long id) {
        return jdbcTemplate.query("""
            SELECT id, api_id, name, enabled, window_minutes, min_requests,
                   min_success_rate, max_p95_ms, created_by, created_at, updated_at
            FROM data_service_slo_rule
            WHERE id = ?
            """, this::mapRule, id).stream().findFirst();
    }

    @Transactional
    public SloRuleRecord save(
        Long id,
        long apiId,
        String name,
        boolean enabled,
        int windowMinutes,
        int minRequests,
        BigDecimal minSuccessRate,
        long maxP95Ms,
        String actor
    ) {
        if (id == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                    INSERT INTO data_service_slo_rule
                      (api_id, name, enabled, window_minutes, min_requests,
                       min_success_rate, max_p95_ms, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
                statement.setLong(1, apiId);
                statement.setString(2, name);
                statement.setBoolean(3, enabled);
                statement.setInt(4, windowMinutes);
                statement.setInt(5, minRequests);
                statement.setBigDecimal(6, minSuccessRate);
                statement.setLong(7, maxP95Ms);
                statement.setString(8, actor);
                return statement;
            }, keyHolder);
            id = keyHolder.getKey().longValue();
        } else {
            jdbcTemplate.update("""
                UPDATE data_service_slo_rule
                SET api_id = ?, name = ?, enabled = ?, window_minutes = ?,
                    min_requests = ?, min_success_rate = ?, max_p95_ms = ?
                WHERE id = ?
                """,
                apiId, name, enabled, windowMinutes, minRequests,
                minSuccessRate, maxP95Ms, id
            );
        }
        return findRule(id).orElseThrow();
    }

    public ApiSloStatistics statistics(long apiId, Instant since) {
        List<CallSample> samples = jdbcTemplate.query("""
            SELECT status_code, elapsed_ms
            FROM data_service_call_log
            WHERE api_id = ? AND test_call = 0 AND occurred_at >= ?
            ORDER BY elapsed_ms
            """, (rs, rowNum) -> new CallSample(
            rs.getInt("status_code"),
            rs.getLong("elapsed_ms")
        ), apiId, Timestamp.from(since));
        if (samples.isEmpty()) {
            return new ApiSloStatistics(0, BigDecimal.ZERO, 0);
        }
        long successes = samples.stream().filter(sample -> sample.statusCode() < 400).count();
        BigDecimal successRate = BigDecimal.valueOf(successes)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(samples.size()), 3, RoundingMode.HALF_UP);
        int p95Index = Math.max(0, (int) Math.ceil(samples.size() * 0.95) - 1);
        return new ApiSloStatistics(
            samples.size(),
            successRate,
            samples.get(p95Index).elapsedMs()
        );
    }

    public List<AlertEventRecord> findAlerts(int limit) {
        return jdbcTemplate.query("""
            SELECT id, rule_id, api_id, alert_type, status, observed_value,
                   threshold_value, sample_count, message, acknowledged_by,
                   acknowledged_at, resolved_at, opened_at, updated_at
            FROM data_service_alert_event
            ORDER BY id DESC
            LIMIT ?
            """, this::mapAlert, limit);
    }

    public Optional<AlertEventRecord> findActiveAlert(long ruleId, String alertType) {
        return jdbcTemplate.query("""
            SELECT id, rule_id, api_id, alert_type, status, observed_value,
                   threshold_value, sample_count, message, acknowledged_by,
                   acknowledged_at, resolved_at, opened_at, updated_at
            FROM data_service_alert_event
            WHERE rule_id = ? AND alert_type = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
            ORDER BY id DESC
            LIMIT 1
            """, this::mapAlert, ruleId, alertType).stream().findFirst();
    }

    public Optional<AlertEventRecord> openOrUpdateAlert(
        SloRuleRecord rule,
        String alertType,
        BigDecimal observed,
        BigDecimal threshold,
        int sampleCount,
        String message
    ) {
        Optional<AlertEventRecord> active = findActiveAlert(rule.id(), alertType);
        if (active.isPresent()) {
            jdbcTemplate.update("""
                UPDATE data_service_alert_event
                SET observed_value = ?, threshold_value = ?, sample_count = ?, message = ?
                WHERE id = ?
                """, observed, threshold, sampleCount, message, active.get().id());
            return Optional.empty();
        }
        try {
            jdbcTemplate.update("""
                INSERT INTO data_service_alert_event
                  (rule_id, api_id, alert_type, status, observed_value,
                   threshold_value, sample_count, message)
                VALUES (?, ?, ?, 'OPEN', ?, ?, ?, ?)
                """,
                rule.id(), rule.apiId(), alertType, observed, threshold, sampleCount, message
            );
            return findActiveAlert(rule.id(), alertType);
        } catch (DuplicateKeyException exception) {
            jdbcTemplate.update("""
                UPDATE data_service_alert_event
                SET observed_value = ?, threshold_value = ?, sample_count = ?, message = ?
                WHERE rule_id = ? AND alert_type = ? AND active_flag = 1
                """,
                observed, threshold, sampleCount, message, rule.id(), alertType
            );
            return Optional.empty();
        }
    }

    public Optional<AlertEventRecord> resolveActiveAlert(long ruleId, String alertType) {
        Optional<AlertEventRecord> active = findActiveAlert(ruleId, alertType);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        jdbcTemplate.update("""
            UPDATE data_service_alert_event
            SET status = 'RESOLVED', active_flag = NULL, resolved_at = CURRENT_TIMESTAMP
            WHERE rule_id = ? AND alert_type = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
            """, ruleId, alertType);
        return findAlert(active.get().id());
    }

    public AlertEventRecord acknowledge(long alertId, String actor) {
        jdbcTemplate.update("""
            UPDATE data_service_alert_event
            SET status = 'ACKNOWLEDGED', acknowledged_by = ?,
                acknowledged_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'OPEN'
            """, actor, alertId);
        return findAlert(alertId).orElseThrow();
    }

    public AlertEventRecord resolve(long alertId) {
        jdbcTemplate.update("""
            UPDATE data_service_alert_event
            SET status = 'RESOLVED', active_flag = NULL, resolved_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
            """, alertId);
        return findAlert(alertId).orElseThrow();
    }

    public Optional<AlertEventRecord> findAlert(long id) {
        return jdbcTemplate.query("""
            SELECT id, rule_id, api_id, alert_type, status, observed_value,
                   threshold_value, sample_count, message, acknowledged_by,
                   acknowledged_at, resolved_at, opened_at, updated_at
            FROM data_service_alert_event
            WHERE id = ?
            """, this::mapAlert, id).stream().findFirst();
    }

    private SloRuleRecord mapRule(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SloRuleRecord(
            rs.getLong("id"),
            rs.getLong("api_id"),
            rs.getString("name"),
            rs.getBoolean("enabled"),
            rs.getInt("window_minutes"),
            rs.getInt("min_requests"),
            rs.getBigDecimal("min_success_rate"),
            rs.getLong("max_p95_ms"),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private AlertEventRecord mapAlert(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AlertEventRecord(
            rs.getLong("id"),
            rs.getLong("rule_id"),
            rs.getLong("api_id"),
            rs.getString("alert_type"),
            rs.getString("status"),
            rs.getBigDecimal("observed_value"),
            rs.getBigDecimal("threshold_value"),
            rs.getInt("sample_count"),
            rs.getString("message"),
            rs.getString("acknowledged_by"),
            toInstant(rs.getTimestamp("acknowledged_at")),
            toInstant(rs.getTimestamp("resolved_at")),
            toInstant(rs.getTimestamp("opened_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record ApiSloStatistics(
        int sampleCount,
        BigDecimal successRate,
        long p95Ms
    ) {
    }

    private record CallSample(int statusCode, long elapsedMs) {
    }
}
