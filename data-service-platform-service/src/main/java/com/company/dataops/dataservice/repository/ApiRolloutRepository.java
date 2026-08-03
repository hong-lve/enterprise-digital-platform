package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.ApiRolloutRecord;
import com.company.dataops.dataservice.domain.RolloutEventRecord;
import com.company.dataops.dataservice.domain.RolloutHealthPolicy;
import com.company.dataops.dataservice.domain.RolloutHealthSnapshot;
import com.company.dataops.dataservice.domain.RolloutStage;
import com.company.dataops.dataservice.domain.RolloutVariantMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ApiRolloutRepository {
    private static final String COLUMNS = """
        SELECT id, api_id, baseline_version_no, candidate_version_no, percentage,
               automated, stage_plan_json, current_stage_index, stage_started_at,
               next_evaluation_at, health_policy_json, failure_action,
               paused_reason, paused_by, paused_at,
               application_ids_json, ip_rules_json, status, note, started_by, started_at,
               updated_by, updated_at, finished_by, finished_at
        FROM data_service_api_rollout
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ApiRolloutRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<ApiRolloutRecord> findActive(long apiId) {
        return jdbcTemplate.query(
            COLUMNS + " WHERE api_id = ? AND status IN ('ACTIVE', 'PAUSED') ORDER BY id DESC LIMIT 1",
            this::map,
            apiId
        ).stream().findFirst();
    }

    public Optional<ApiRolloutRecord> findById(long id) {
        return jdbcTemplate.query(COLUMNS + " WHERE id = ?", this::map, id)
            .stream().findFirst();
    }

    public List<ApiRolloutRecord> findByApiId(long apiId) {
        return jdbcTemplate.query(
            COLUMNS + " WHERE api_id = ? ORDER BY id DESC LIMIT 50",
            this::map,
            apiId
        );
    }

    @Transactional
    public ApiRolloutRecord create(
        long apiId,
        int baselineVersionNo,
        int candidateVersionNo,
        int percentage,
        Set<Long> applicationIds,
        List<String> ipRules,
        String note,
        String actor,
        boolean automated,
        List<RolloutStage> stages,
        RolloutHealthPolicy healthPolicy,
        String failureAction,
        Instant nextEvaluationAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_api_rollout
                  (api_id, baseline_version_no, candidate_version_no, percentage,
                   automated, stage_plan_json, current_stage_index, stage_started_at,
                   next_evaluation_at, health_policy_json, failure_action,
                   application_ids_json, ip_rules_json, status, note, started_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, ?, ?, ?,
                        ?, ?, 'ACTIVE', ?, ?, ?)
                """, new String[]{"id"});
            statement.setLong(1, apiId);
            statement.setInt(2, baselineVersionNo);
            statement.setInt(3, candidateVersionNo);
            statement.setInt(4, percentage);
            statement.setBoolean(5, automated);
            statement.setString(6, automated ? write(stages) : null);
            statement.setTimestamp(7, nextEvaluationAt == null ? null : Timestamp.from(nextEvaluationAt));
            statement.setString(8, automated ? write(healthPolicy) : null);
            statement.setString(9, failureAction);
            statement.setString(10, write(applicationIds));
            statement.setString(11, write(ipRules));
            statement.setString(12, note);
            statement.setString(13, actor);
            statement.setString(14, actor);
            return statement;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public ApiRolloutRecord update(
        long id,
        int percentage,
        Set<Long> applicationIds,
        List<String> ipRules,
        String note,
        String actor
    ) {
        int updated = jdbcTemplate.update("""
            UPDATE data_service_api_rollout
            SET percentage = ?, application_ids_json = ?, ip_rules_json = ?,
                note = ?, updated_by = ?, automated = 0, stage_plan_json = NULL,
                health_policy_json = NULL, next_evaluation_at = NULL,
                lock_owner = NULL, lock_until = NULL
            WHERE id = ? AND status IN ('ACTIVE', 'PAUSED')
            """, percentage, write(applicationIds), write(ipRules), note, actor, id);
        if (updated == 0) {
            throw new IllegalStateException("Active rollout not found");
        }
        return findById(id).orElseThrow();
    }

    public void finish(long id, String status, String actor) {
        int updated = jdbcTemplate.update("""
            UPDATE data_service_api_rollout
            SET status = ?, updated_by = ?, finished_by = ?, finished_at = CURRENT_TIMESTAMP,
                next_evaluation_at = NULL, lock_owner = NULL, lock_until = NULL
            WHERE id = ? AND status IN ('ACTIVE', 'PAUSED')
            """, status, actor, actor, id);
        if (updated == 0) {
            throw new IllegalStateException("Active rollout not found");
        }
    }

    public List<RolloutVariantMetrics> metrics(long rolloutId) {
        return jdbcTemplate.query("""
            SELECT rollout_variant, routed_version_no,
                   COUNT(*) AS request_count,
                   SUM(CASE WHEN status_code < 400 THEN 1 ELSE 0 END) AS success_count,
                   SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END) AS error_count,
                   ROUND(100.0 * SUM(CASE WHEN status_code < 400 THEN 1 ELSE 0 END) / COUNT(*), 2)
                     AS success_rate,
                   ROUND(AVG(elapsed_ms), 2) AS average_elapsed_ms,
                   MAX(elapsed_ms) AS maximum_elapsed_ms
            FROM data_service_call_log
            WHERE rollout_id = ? AND test_call = 0
            GROUP BY rollout_variant, routed_version_no
            ORDER BY rollout_variant
            """, (rs, rowNum) -> new RolloutVariantMetrics(
                rs.getString("rollout_variant"),
                (Integer) rs.getObject("routed_version_no"),
                rs.getLong("request_count"),
                rs.getLong("success_count"),
                rs.getLong("error_count"),
                rs.getDouble("success_rate"),
                rs.getDouble("average_elapsed_ms"),
                rs.getLong("maximum_elapsed_ms")
            ), rolloutId);
    }

    public RolloutHealthSnapshot health(long rolloutId, Instant since) {
        return jdbcTemplate.query("""
            WITH samples AS (
              SELECT status_code, elapsed_ms,
                     ROW_NUMBER() OVER (ORDER BY elapsed_ms) AS latency_rank,
                     COUNT(*) OVER () AS total_count
              FROM data_service_call_log
              WHERE rollout_id = ? AND rollout_variant = 'CANARY'
                AND test_call = 0 AND occurred_at >= ?
            )
            SELECT COUNT(*) AS request_count,
                   COALESCE(SUM(CASE WHEN status_code < 400 THEN 1 ELSE 0 END), 0) AS success_count,
                   COALESCE(SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END), 0) AS error_count,
                   COALESCE(ROUND(100.0 * SUM(CASE WHEN status_code < 400 THEN 1 ELSE 0 END)
                     / NULLIF(COUNT(*), 0), 2), 0) AS success_rate,
                   COALESCE(ROUND(100.0 * SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END)
                     / NULLIF(COUNT(*), 0), 2), 0) AS error_rate,
                   COALESCE(ROUND(AVG(elapsed_ms), 2), 0) AS average_elapsed_ms,
                   COALESCE(MIN(CASE WHEN latency_rank >= CEIL(total_count * 0.95)
                     THEN elapsed_ms END), 0) AS p95_elapsed_ms,
                   COALESCE(MIN(CASE WHEN latency_rank >= CEIL(total_count * 0.99)
                     THEN elapsed_ms END), 0) AS p99_elapsed_ms
            FROM samples
            """, rs -> {
            if (!rs.next()) {
                return emptyHealth(since);
            }
            return new RolloutHealthSnapshot(
                rs.getLong("request_count"),
                rs.getLong("success_count"),
                rs.getLong("error_count"),
                rs.getDouble("success_rate"),
                rs.getDouble("error_rate"),
                rs.getDouble("average_elapsed_ms"),
                rs.getLong("p95_elapsed_ms"),
                rs.getLong("p99_elapsed_ms"),
                since
            );
        }, rolloutId, Timestamp.from(since));
    }

    public List<Long> claimDue(String owner, int limit, int leaseSeconds) {
        List<Long> candidates = jdbcTemplate.queryForList("""
            SELECT id
            FROM data_service_api_rollout
            WHERE status = 'ACTIVE' AND automated = 1
              AND next_evaluation_at <= CURRENT_TIMESTAMP
              AND (lock_until IS NULL OR lock_until < CURRENT_TIMESTAMP)
            ORDER BY next_evaluation_at
            LIMIT ?
            """, Long.class, limit);
        List<Long> claimed = new ArrayList<>();
        for (Long id : candidates) {
            int updated = jdbcTemplate.update("""
                UPDATE data_service_api_rollout
                SET lock_owner = ?, lock_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND)
                WHERE id = ? AND status = 'ACTIVE' AND automated = 1
                  AND next_evaluation_at <= CURRENT_TIMESTAMP
                  AND (lock_until IS NULL OR lock_until < CURRENT_TIMESTAMP)
                """, owner, leaseSeconds, id);
            if (updated == 1) {
                claimed.add(id);
            }
        }
        return claimed;
    }

    public boolean ownsLock(long id, String owner) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM data_service_api_rollout
            WHERE id = ? AND lock_owner = ? AND lock_until >= CURRENT_TIMESTAMP
            """, Integer.class, id, owner);
        return count != null && count == 1;
    }

    public void releaseLock(long id, String owner) {
        jdbcTemplate.update("""
            UPDATE data_service_api_rollout
            SET lock_owner = NULL, lock_until = NULL
            WHERE id = ? AND lock_owner = ?
            """, id, owner);
    }

    public ApiRolloutRecord advanceStage(
        long id,
        int stageIndex,
        int percentage,
        Instant nextEvaluationAt,
        String actor
    ) {
        int updated = jdbcTemplate.update("""
            UPDATE data_service_api_rollout
            SET current_stage_index = ?, percentage = ?, stage_started_at = CURRENT_TIMESTAMP,
                next_evaluation_at = ?, updated_by = ?, lock_owner = NULL, lock_until = NULL
            WHERE id = ? AND status = 'ACTIVE'
            """, stageIndex, percentage, Timestamp.from(nextEvaluationAt), actor, id);
        if (updated == 0) {
            throw new IllegalStateException("Active automated rollout not found");
        }
        return findById(id).orElseThrow();
    }

    public ApiRolloutRecord pause(long id, String reason, String actor) {
        int updated = jdbcTemplate.update("""
            UPDATE data_service_api_rollout
            SET status = 'PAUSED', paused_reason = ?, paused_by = ?,
                paused_at = CURRENT_TIMESTAMP, updated_by = ?,
                next_evaluation_at = NULL, lock_owner = NULL, lock_until = NULL
            WHERE id = ? AND status = 'ACTIVE'
            """, reason, actor, actor, id);
        if (updated == 0) {
            throw new IllegalStateException("Active rollout not found");
        }
        return findById(id).orElseThrow();
    }

    public ApiRolloutRecord resume(long id, Instant nextEvaluationAt, String actor) {
        int updated = jdbcTemplate.update("""
            UPDATE data_service_api_rollout
            SET status = 'ACTIVE', stage_started_at = CURRENT_TIMESTAMP,
                next_evaluation_at = ?, paused_reason = NULL, paused_by = NULL,
                paused_at = NULL, updated_by = ?, lock_owner = NULL, lock_until = NULL
            WHERE id = ? AND status = 'PAUSED' AND automated = 1
            """, Timestamp.from(nextEvaluationAt), actor, id);
        if (updated == 0) {
            throw new IllegalStateException("Paused automated rollout not found");
        }
        return findById(id).orElseThrow();
    }

    public void saveEvent(
        long rolloutId,
        String eventType,
        Integer stageIndex,
        Integer percentage,
        String message,
        String actor,
        Map<String, Object> details
    ) {
        jdbcTemplate.update("""
            INSERT INTO data_service_api_rollout_event
              (rollout_id, event_type, stage_index, percentage, message, actor, details_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, rolloutId, eventType, stageIndex, percentage, message, actor,
            details == null || details.isEmpty() ? null : write(details));
    }

    public List<RolloutEventRecord> events(long rolloutId) {
        return jdbcTemplate.query("""
            SELECT id, rollout_id, event_type, stage_index, percentage, message,
                   actor, details_json, occurred_at
            FROM data_service_api_rollout_event
            WHERE rollout_id = ?
            ORDER BY id DESC
            LIMIT 200
            """, (rs, rowNum) -> new RolloutEventRecord(
                rs.getLong("id"),
                rs.getLong("rollout_id"),
                rs.getString("event_type"),
                (Integer) rs.getObject("stage_index"),
                (Integer) rs.getObject("percentage"),
                rs.getString("message"),
                rs.getString("actor"),
                readMap(rs.getString("details_json")),
                instant(rs.getTimestamp("occurred_at"))
            ), rolloutId);
    }

    private ApiRolloutRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ApiRolloutRecord(
            rs.getLong("id"),
            rs.getLong("api_id"),
            rs.getInt("baseline_version_no"),
            rs.getInt("candidate_version_no"),
            rs.getInt("percentage"),
            rs.getBoolean("automated"),
            readStages(rs.getString("stage_plan_json")),
            rs.getInt("current_stage_index"),
            instant(rs.getTimestamp("stage_started_at")),
            instant(rs.getTimestamp("next_evaluation_at")),
            readPolicy(rs.getString("health_policy_json")),
            rs.getString("failure_action"),
            rs.getString("paused_reason"),
            rs.getString("paused_by"),
            instant(rs.getTimestamp("paused_at")),
            readSet(rs.getString("application_ids_json")),
            readList(rs.getString("ip_rules_json")),
            rs.getString("status"),
            rs.getString("note"),
            rs.getString("started_by"),
            instant(rs.getTimestamp("started_at")),
            rs.getString("updated_by"),
            instant(rs.getTimestamp("updated_at")),
            rs.getString("finished_by"),
            instant(rs.getTimestamp("finished_at"))
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize rollout rules", exception);
        }
    }

    private Set<Long> readSet(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid rollout application rules", exception);
        }
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid rollout IP rules", exception);
        }
    }

    private List<RolloutStage> readStages(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid rollout stage plan", exception);
        }
    }

    private RolloutHealthPolicy readPolicy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, RolloutHealthPolicy.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid rollout health policy", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid rollout event details", exception);
        }
    }

    private RolloutHealthSnapshot emptyHealth(Instant since) {
        return new RolloutHealthSnapshot(0L, 0L, 0L, 0.0, 0.0, 0.0, 0L, 0L, since);
    }

    private java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
