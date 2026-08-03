package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.ApplicationCredential;
import com.company.dataops.dataservice.domain.ApplicationRecord;
import com.company.dataops.dataservice.domain.ApplicationSecretVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ApplicationRepository {
    private final JdbcTemplate jdbcTemplate;

    public ApplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ApplicationRecord> findAll() {
        Map<Long, ApplicationRecordBuilder> records = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT a.id, a.app_key, a.name, a.description, a.status, a.qps_limit,
                   a.secret_version, a.last_rotated_at, a.created_at, a.updated_at,
                   aa.api_id
            FROM data_service_app a
            LEFT JOIN data_service_api_subscription aa
              ON aa.app_id = a.id AND aa.status = 'APPROVED'
            ORDER BY a.id DESC, aa.api_id
            """, rs -> {
            long id = rs.getLong("id");
            ApplicationRecordBuilder builder = records.computeIfAbsent(id, ignored -> builder(rs));
            Long apiId = (Long) rs.getObject("api_id");
            if (apiId != null) {
                builder.apiIds.add(apiId);
            }
        });
        return records.values().stream().map(ApplicationRecordBuilder::build).toList();
    }

    public Optional<ApplicationRecord> findById(long id) {
        return findAll().stream().filter(record -> record.id() == id).findFirst();
    }

    public Optional<ApplicationCredential> findCredential(String appKey) {
        return jdbcTemplate.query("""
            SELECT id, app_key, app_secret_ciphertext, status, qps_limit, secret_version
            FROM data_service_app
            WHERE app_key = ?
            """, this::mapCredential, appKey).stream().findFirst();
    }

    @Transactional
    public ApplicationRecord create(
        String appKey,
        String secretHash,
        String encryptedSecret,
        String name,
        String description,
        int qpsLimit
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_app
                    (app_key, app_secret_hash, app_secret_ciphertext, name, description,
                     status, qps_limit, last_rotated_at)
                VALUES (?, ?, ?, ?, ?, 'ENABLED', ?, CURRENT_TIMESTAMP)
                """, new String[]{"id"});
            statement.setString(1, appKey);
            statement.setString(2, secretHash);
            statement.setString(3, encryptedSecret);
            statement.setString(4, name);
            statement.setString(5, description);
            statement.setInt(6, qpsLimit);
            return statement;
        }, keyHolder);
        long appId = keyHolder.getKey().longValue();
        jdbcTemplate.update("""
            INSERT INTO data_service_app_secret
              (app_id, secret_version, secret_hash, secret_ciphertext, status, created_by)
            VALUES (?, 1, ?, ?, 'ACTIVE', 'application-create')
            """, appId, secretHash, encryptedSecret);
        return findById(appId).orElseThrow();
    }

    public ApplicationRecord updateStatus(long id, String status) {
        int updated = jdbcTemplate.update(
            "UPDATE data_service_app SET status = ? WHERE id = ?",
            status,
            id
        );
        if (updated == 0) {
            throw new IllegalArgumentException("应用不存在");
        }
        return findById(id).orElseThrow();
    }

    @Transactional
    public ApplicationRecord rotateSecret(
        long id,
        String secretHash,
        String encryptedSecret,
        Duration gracePeriod,
        String actor
    ) {
        Integer currentVersion = jdbcTemplate.query("""
            SELECT secret_version
            FROM data_service_app
            WHERE id = ?
            FOR UPDATE
            """, rs -> rs.next() ? rs.getInt(1) : null, id);
        if (currentVersion == null) {
            throw new IllegalArgumentException("应用不存在");
        }
        jdbcTemplate.update("""
            UPDATE data_service_app_secret
            SET status = 'REVOKED', revoked_by = ?, revoked_at = CURRENT_TIMESTAMP,
                expires_at = CURRENT_TIMESTAMP
            WHERE app_id = ? AND status = 'GRACE'
            """, actor, id);
        jdbcTemplate.update("""
            UPDATE data_service_app_secret
            SET status = 'GRACE', expires_at = ?
            WHERE app_id = ? AND status = 'ACTIVE'
            """, Timestamp.from(Instant.now().plus(gracePeriod)), id);
        int nextVersion = currentVersion + 1;
        jdbcTemplate.update("""
            INSERT INTO data_service_app_secret
              (app_id, secret_version, secret_hash, secret_ciphertext, status, created_by)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?)
            """, id, nextVersion, secretHash, encryptedSecret, actor);
        jdbcTemplate.update("""
            UPDATE data_service_app
            SET app_secret_hash = ?, app_secret_ciphertext = ?,
                secret_version = ?, last_rotated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, secretHash, encryptedSecret, nextVersion, id);
        return findById(id).orElseThrow();
    }

    public List<ApplicationSecretVersion> findSecretVersions(long appId) {
        return jdbcTemplate.query("""
            SELECT id, app_id, secret_version, status, expires_at, last_used_at,
                   created_by, created_at, revoked_by, revoked_at
            FROM data_service_app_secret
            WHERE app_id = ?
            ORDER BY secret_version DESC
            """, (rs, rowNum) -> new ApplicationSecretVersion(
            rs.getLong("id"),
            rs.getLong("app_id"),
            rs.getInt("secret_version"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("last_used_at")),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            rs.getString("revoked_by"),
            toInstant(rs.getTimestamp("revoked_at"))
        ), appId);
    }

    public List<UsableApplicationSecret> findUsableSecrets(String appKey, Integer requestedVersion) {
        return jdbcTemplate.query("""
            SELECT app.id AS app_id, app.app_key, app.status AS app_status, app.qps_limit,
                   secret.id AS secret_id, secret.secret_version, secret.secret_ciphertext
            FROM data_service_app app
            JOIN data_service_app_secret secret ON secret.app_id = app.id
            WHERE app.app_key = ?
              AND (? IS NULL OR secret.secret_version = ?)
              AND (
                secret.status = 'ACTIVE'
                OR (secret.status = 'GRACE' AND secret.expires_at > CURRENT_TIMESTAMP)
              )
            ORDER BY secret.secret_version DESC
            """, (rs, rowNum) -> new UsableApplicationSecret(
            rs.getLong("app_id"),
            rs.getString("app_key"),
            rs.getString("app_status"),
            rs.getInt("qps_limit"),
            rs.getLong("secret_id"),
            rs.getInt("secret_version"),
            rs.getString("secret_ciphertext")
        ), appKey, requestedVersion, requestedVersion);
    }

    public void markSecretUsed(long secretId) {
        jdbcTemplate.update(
            "UPDATE data_service_app_secret SET last_used_at = CURRENT_TIMESTAMP WHERE id = ?",
            secretId
        );
    }

    public ApplicationSecretVersion revokeSecret(long appId, int version, String actor) {
        int affected = jdbcTemplate.update("""
            UPDATE data_service_app_secret
            SET status = 'REVOKED', revoked_by = ?, revoked_at = CURRENT_TIMESTAMP,
                expires_at = CURRENT_TIMESTAMP
            WHERE app_id = ? AND secret_version = ? AND status = 'GRACE'
            """, actor, appId, version);
        if (affected != 1) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Only a grace-period secret can be revoked"
            );
        }
        return findSecretVersions(appId).stream()
            .filter(item -> item.secretVersion() == version)
            .findFirst()
            .orElseThrow();
    }

    @Transactional
    public ApplicationRecord replaceAuthorizations(long appId, List<Long> apiIds, String grantedBy) {
        if (findById(appId).isEmpty()) {
            throw new IllegalArgumentException("应用不存在");
        }
        jdbcTemplate.update("DELETE FROM data_service_app_api WHERE app_id = ?", appId);
        for (Long apiId : apiIds.stream().distinct().toList()) {
            jdbcTemplate.update("""
                INSERT INTO data_service_app_api (app_id, api_id, granted_by)
                VALUES (?, ?, ?)
                """, appId, apiId, grantedBy);
        }
        return findById(appId).orElseThrow();
    }

    public boolean isAuthorized(long appId, long apiId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM data_service_api_subscription
            WHERE app_id = ? AND api_id = ? AND status = 'APPROVED'
              AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
              AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
            """, Integer.class, appId, apiId);
        return count != null && count > 0;
    }

    private ApplicationCredential mapCredential(ResultSet rs, int rowNum) throws SQLException {
        return new ApplicationCredential(
            rs.getLong("id"),
            rs.getString("app_key"),
            rs.getString("app_secret_ciphertext"),
            rs.getString("status"),
            rs.getInt("qps_limit"),
            rs.getInt("secret_version")
        );
    }

    private ApplicationRecordBuilder builder(ResultSet rs) {
        try {
            return new ApplicationRecordBuilder(
                rs.getLong("id"),
                rs.getString("app_key"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getInt("qps_limit"),
                rs.getInt("secret_version"),
                toInstant(rs.getTimestamp("last_rotated_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("应用数据读取失败", exception);
        }
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final class ApplicationRecordBuilder {
        private final long id;
        private final String appKey;
        private final String name;
        private final String description;
        private final String status;
        private final int qpsLimit;
        private final int secretVersion;
        private final java.time.Instant lastRotatedAt;
        private final java.time.Instant createdAt;
        private final java.time.Instant updatedAt;
        private final List<Long> apiIds = new ArrayList<>();

        private ApplicationRecordBuilder(
            long id,
            String appKey,
            String name,
            String description,
            String status,
            int qpsLimit,
            int secretVersion,
            java.time.Instant lastRotatedAt,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
        ) {
            this.id = id;
            this.appKey = appKey;
            this.name = name;
            this.description = description;
            this.status = status;
            this.qpsLimit = qpsLimit;
            this.secretVersion = secretVersion;
            this.lastRotatedAt = lastRotatedAt;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        private ApplicationRecord build() {
            return new ApplicationRecord(
                id, appKey, name, description, status, qpsLimit, secretVersion,
                lastRotatedAt, createdAt, updatedAt, List.copyOf(apiIds)
            );
        }
    }

    public record UsableApplicationSecret(
        long appId,
        String appKey,
        String appStatus,
        int qpsLimit,
        long secretId,
        int secretVersion,
        String encryptedSecret
    ) {
    }
}
