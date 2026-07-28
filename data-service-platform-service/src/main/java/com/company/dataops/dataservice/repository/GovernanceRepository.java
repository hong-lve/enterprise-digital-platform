package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.NotificationChannelRecord;
import com.company.dataops.dataservice.domain.NotificationDeliveryRecord;
import com.company.dataops.dataservice.domain.OperationAuditRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GovernanceRepository {
    private final JdbcTemplate jdbcTemplate;

    public GovernanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void saveAudit(
        String actor,
        String clientIp,
        String traceId,
        String httpMethod,
        String requestPath,
        String operation,
        String resourceId,
        String status,
        int statusCode,
        String errorMessage
    ) {
        String previousHash = jdbcTemplate.query("""
            SELECT last_hash
            FROM data_service_audit_chain_head
            WHERE id = 1
            FOR UPDATE
            """, rs -> rs.next() ? rs.getString(1) : null);
        String safeError = truncate(errorMessage);
        String recordHash = sha256(String.join("|",
            value(previousHash), value(actor), value(clientIp), value(traceId),
            value(httpMethod), value(requestPath), value(operation), value(resourceId),
            value(status), String.valueOf(statusCode), value(safeError)
        ));
        jdbcTemplate.update("""
            INSERT INTO data_service_operation_audit
              (actor, client_ip, trace_id, http_method, request_path, operation,
               resource_id, status, status_code, error_message, previous_hash, record_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            actor, clientIp, traceId, httpMethod, requestPath, operation,
            resourceId, status, statusCode, safeError, previousHash, recordHash
        );
        jdbcTemplate.update(
            "UPDATE data_service_audit_chain_head SET last_hash = ? WHERE id = 1",
            recordHash
        );
    }

    public List<OperationAuditRecord> findAudits(int limit) {
        return jdbcTemplate.query("""
            SELECT id, actor, client_ip, trace_id, http_method, request_path,
                   operation, resource_id, status, status_code, error_message,
                   previous_hash, record_hash, occurred_at
            FROM data_service_operation_audit
            ORDER BY id DESC
            LIMIT ?
            """, (rs, rowNum) -> new OperationAuditRecord(
            rs.getLong("id"),
            rs.getString("actor"),
            rs.getString("client_ip"),
            rs.getString("trace_id"),
            rs.getString("http_method"),
            rs.getString("request_path"),
            rs.getString("operation"),
            rs.getString("resource_id"),
            rs.getString("status"),
            rs.getInt("status_code"),
            rs.getString("error_message"),
            rs.getString("previous_hash"),
            rs.getString("record_hash"),
            toInstant(rs.getTimestamp("occurred_at"))
        ), limit);
    }

    public AuditIntegrity verifyAuditIntegrity() {
        List<OperationAuditRecord> records = jdbcTemplate.query("""
            SELECT id, actor, client_ip, trace_id, http_method, request_path,
                   operation, resource_id, status, status_code, error_message,
                   previous_hash, record_hash, occurred_at
            FROM data_service_operation_audit
            ORDER BY id
            """, (rs, rowNum) -> new OperationAuditRecord(
            rs.getLong("id"),
            rs.getString("actor"),
            rs.getString("client_ip"),
            rs.getString("trace_id"),
            rs.getString("http_method"),
            rs.getString("request_path"),
            rs.getString("operation"),
            rs.getString("resource_id"),
            rs.getString("status"),
            rs.getInt("status_code"),
            rs.getString("error_message"),
            rs.getString("previous_hash"),
            rs.getString("record_hash"),
            toInstant(rs.getTimestamp("occurred_at"))
        ));
        String previous = null;
        for (OperationAuditRecord record : records) {
            String expected = sha256(String.join("|",
                value(previous), value(record.actor()), value(record.clientIp()), value(record.traceId()),
                value(record.httpMethod()), value(record.requestPath()), value(record.operation()),
                value(record.resourceId()), value(record.status()), String.valueOf(record.statusCode()),
                value(record.errorMessage())
            ));
            if (!java.util.Objects.equals(previous, record.previousHash())
                || !expected.equals(record.recordHash())) {
                return new AuditIntegrity(false, records.size(), record.id());
            }
            previous = record.recordHash();
        }
        return new AuditIntegrity(true, records.size(), null);
    }

    public List<NotificationChannelRecord> findChannels() {
        return jdbcTemplate.query("""
            SELECT id, name, channel_type, endpoint_ciphertext, enabled,
                   created_by, created_at, updated_at
            FROM data_service_notification_channel
            ORDER BY id DESC
            """, (rs, rowNum) -> new NotificationChannelRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("channel_type"),
            rs.getString("endpoint_ciphertext") != null,
            rs.getBoolean("enabled"),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        ));
    }

    public Optional<ChannelCredential> findChannelCredential(long id) {
        return jdbcTemplate.query("""
            SELECT id, name, channel_type, endpoint_ciphertext, enabled
            FROM data_service_notification_channel
            WHERE id = ?
            """, (rs, rowNum) -> new ChannelCredential(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("channel_type"),
            rs.getString("endpoint_ciphertext"),
            rs.getBoolean("enabled")
        ), id).stream().findFirst();
    }

    public NotificationChannelRecord createChannel(
        String name,
        String channelType,
        String endpointCiphertext,
        boolean enabled,
        String actor
    ) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_notification_channel
                  (name, channel_type, endpoint_ciphertext, enabled, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, new String[]{"id"});
            statement.setString(1, name);
            statement.setString(2, channelType);
            statement.setString(3, endpointCiphertext);
            statement.setBoolean(4, enabled);
            statement.setString(5, actor);
            return statement;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return findChannels().stream().filter(item -> item.id() == id).findFirst().orElseThrow();
    }

    public NotificationChannelRecord updateChannel(
        long id,
        String name,
        String channelType,
        String endpointCiphertext,
        boolean enabled
    ) {
        if (endpointCiphertext == null) {
            jdbcTemplate.update("""
                UPDATE data_service_notification_channel
                SET name = ?, channel_type = ?, enabled = ?
                WHERE id = ?
                """, name, channelType, enabled, id);
        } else {
            jdbcTemplate.update("""
                UPDATE data_service_notification_channel
                SET name = ?, channel_type = ?, endpoint_ciphertext = ?, enabled = ?
                WHERE id = ?
                """, name, channelType, endpointCiphertext, enabled, id);
        }
        return findChannels().stream().filter(item -> item.id() == id).findFirst().orElseThrow();
    }

    public void enqueueForEnabledChannels(Long alertEventId, String eventType, String payloadJson) {
        jdbcTemplate.update("""
            INSERT INTO data_service_notification_delivery
              (channel_id, alert_event_id, event_type, status, payload_json)
            SELECT id, ?, ?, 'PENDING', ?
            FROM data_service_notification_channel
            WHERE enabled = 1
            """, alertEventId, eventType, payloadJson);
    }

    public void enqueueForChannel(long channelId, String eventType, String payloadJson) {
        jdbcTemplate.update("""
            INSERT INTO data_service_notification_delivery
              (channel_id, alert_event_id, event_type, status, payload_json)
            VALUES (?, NULL, ?, 'PENDING', ?)
            """, channelId, eventType, payloadJson);
    }

    @Transactional
    public List<NotificationDeliveryRecord> claimDueDeliveries(int limit) {
        List<NotificationDeliveryRecord> deliveries = jdbcTemplate.query("""
            SELECT id, channel_id, alert_event_id, event_type, status, payload_json,
                   attempts, next_attempt_at, last_error, sent_at, created_at, updated_at
            FROM data_service_notification_delivery
            WHERE (
                status IN ('PENDING', 'RETRY') AND next_attempt_at <= CURRENT_TIMESTAMP
            ) OR (
                status = 'PROCESSING' AND updated_at < ?
            )
            ORDER BY id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """, this::mapDelivery, Timestamp.from(Instant.now().minus(5, ChronoUnit.MINUTES)), limit);
        deliveries.forEach(delivery -> jdbcTemplate.update("""
            UPDATE data_service_notification_delivery
            SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, delivery.id()));
        return deliveries;
    }

    public List<NotificationDeliveryRecord> findDeliveries(int limit) {
        return jdbcTemplate.query("""
            SELECT id, channel_id, alert_event_id, event_type, status, payload_json,
                   attempts, next_attempt_at, last_error, sent_at, created_at, updated_at
            FROM data_service_notification_delivery
            ORDER BY id DESC
            LIMIT ?
            """, this::mapDelivery, limit);
    }

    public void markDeliverySent(long id) {
        jdbcTemplate.update("""
            UPDATE data_service_notification_delivery
            SET status = 'SENT', attempts = attempts + 1, sent_at = CURRENT_TIMESTAMP,
                last_error = NULL
            WHERE id = ?
            """, id);
    }

    public void markDeliveryFailed(long id, int attempts, int maxAttempts, Instant nextAttempt, String error) {
        String status = attempts >= maxAttempts ? "DEAD" : "RETRY";
        jdbcTemplate.update("""
            UPDATE data_service_notification_delivery
            SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ?
            WHERE id = ?
            """, status, attempts, Timestamp.from(nextAttempt), truncate(error), id);
    }

    private NotificationDeliveryRecord mapDelivery(java.sql.ResultSet rs, int rowNum)
        throws java.sql.SQLException {
        return new NotificationDeliveryRecord(
            rs.getLong("id"),
            rs.getLong("channel_id"),
            (Long) rs.getObject("alert_event_id"),
            rs.getString("event_type"),
            rs.getString("status"),
            rs.getString("payload_json"),
            rs.getInt("attempts"),
            toInstant(rs.getTimestamp("next_attempt_at")),
            rs.getString("last_error"),
            toInstant(rs.getTimestamp("sent_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ChannelCredential(
        long id,
        String name,
        String channelType,
        String endpointCiphertext,
        boolean enabled
    ) {
    }

    public record AuditIntegrity(boolean valid, int checkedRecords, Long brokenRecordId) {
    }
}
