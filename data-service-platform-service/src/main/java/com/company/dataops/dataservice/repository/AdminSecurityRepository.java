package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.AdminUserRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminSecurityRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminSecurityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AdminUserRecord> findUserByUsername(String username) {
        return jdbcTemplate.query("""
            SELECT id, username, password_hash, display_name, status, failed_attempts,
                   locked_until, last_login_at
            FROM data_service_admin_user
            WHERE username = ?
            """, this::mapUser, username).stream().findFirst().map(this::withAuthorities);
    }

    public Optional<AdminUserRecord> findUserById(long id) {
        return jdbcTemplate.query("""
            SELECT id, username, password_hash, display_name, status, failed_attempts,
                   locked_until, last_login_at
            FROM data_service_admin_user
            WHERE id = ?
            """, this::mapUser, id).stream().findFirst().map(this::withAuthorities);
    }

    public List<AdminUserRecord> findAllUsers() {
        return jdbcTemplate.query("""
            SELECT id, username, password_hash, display_name, status, failed_attempts,
                   locked_until, last_login_at
            FROM data_service_admin_user
            ORDER BY id
            """, this::mapUser).stream().map(this::withAuthorities).toList();
    }

    public List<RoleOption> findAllRoles() {
        return jdbcTemplate.query("""
            SELECT code, name, description
            FROM data_service_admin_role
            ORDER BY id
            """, (rs, rowNum) -> new RoleOption(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description")
            ));
    }

    public Optional<AdminUserRecord> findActiveUserBySessionHash(String tokenHash) {
        return jdbcTemplate.query("""
            SELECT user.id, user.username, user.password_hash, user.display_name, user.status,
                   user.failed_attempts, user.locked_until, user.last_login_at
            FROM data_service_admin_session session
            JOIN data_service_admin_user user ON user.id = session.user_id
            WHERE session.token_hash = ?
              AND session.revoked_at IS NULL
              AND session.expires_at > CURRENT_TIMESTAMP
              AND user.status = 'ACTIVE'
            """, this::mapUser, tokenHash).stream().findFirst().map(this::withAuthorities);
    }

    public boolean existsUser(String username) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM data_service_admin_user WHERE username = ?",
            Integer.class,
            username
        );
        return count != null && count > 0;
    }

    public AdminUserRecord createBootstrapAdmin(
        String username,
        String passwordHash,
        String displayName
    ) {
        jdbcTemplate.update("""
            INSERT INTO data_service_admin_user (username, password_hash, display_name, status)
            VALUES (?, ?, ?, 'ACTIVE')
            """, username, passwordHash, displayName);
        Long userId = jdbcTemplate.queryForObject(
            "SELECT id FROM data_service_admin_user WHERE username = ?",
            Long.class,
            username
        );
        jdbcTemplate.update("""
            INSERT INTO data_service_admin_user_role (user_id, role_id)
            SELECT ?, id FROM data_service_admin_role WHERE code = 'SUPER_ADMIN'
            """, userId);
        return findUserByUsername(username).orElseThrow();
    }

    @Transactional
    public AdminUserRecord createUser(
        String username,
        String passwordHash,
        String displayName,
        Set<String> roleCodes
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_admin_user
                  (username, password_hash, display_name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, new String[]{"id"});
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, displayName);
            return statement;
        }, keyHolder);
        long userId = keyHolder.getKey().longValue();
        replaceRoles(userId, roleCodes);
        return findUserById(userId).orElseThrow();
    }

    @Transactional
    public AdminUserRecord replaceRoles(long userId, Set<String> roleCodes) {
        jdbcTemplate.update("DELETE FROM data_service_admin_user_role WHERE user_id = ?", userId);
        for (String roleCode : roleCodes) {
            jdbcTemplate.update("""
                INSERT INTO data_service_admin_user_role (user_id, role_id)
                SELECT ?, id FROM data_service_admin_role WHERE code = ?
                """, userId, roleCode);
        }
        return findUserById(userId).orElseThrow();
    }

    public AdminUserRecord updateUserStatus(long userId, String status) {
        jdbcTemplate.update("""
            UPDATE data_service_admin_user
            SET status = ?, failed_attempts = 0, locked_until = NULL
            WHERE id = ?
            """, status, userId);
        if ("DISABLED".equals(status)) {
            revokeAllSessions(userId);
        }
        return findUserById(userId).orElseThrow();
    }

    public void resetPassword(long userId, String passwordHash) {
        jdbcTemplate.update("""
            UPDATE data_service_admin_user
            SET password_hash = ?, password_changed_at = CURRENT_TIMESTAMP,
                failed_attempts = 0, locked_until = NULL
            WHERE id = ?
            """, passwordHash, userId);
        revokeAllSessions(userId);
    }

    public Set<String> findExistingRoleCodes(Set<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(roleCodes.size(), "?"));
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
            "SELECT code FROM data_service_admin_role WHERE code IN (" + placeholders + ")",
            String.class,
            roleCodes.toArray()
        ));
    }

    public void recordFailedAttempt(long userId, int maxAttempts, long lockSeconds) {
        jdbcTemplate.update("""
            UPDATE data_service_admin_user
            SET failed_attempts = failed_attempts + 1,
                locked_until = CASE
                  WHEN failed_attempts + 1 >= ?
                  THEN DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? SECOND)
                  ELSE locked_until
                END
            WHERE id = ?
            """, maxAttempts, lockSeconds, userId);
    }

    public void recordSuccessfulLogin(long userId) {
        jdbcTemplate.update("""
            UPDATE data_service_admin_user
            SET failed_attempts = 0, locked_until = NULL, last_login_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, userId);
    }

    public void createSession(
        long userId,
        String tokenHash,
        Instant expiresAt,
        String clientIp,
        String userAgent
    ) {
        jdbcTemplate.update("""
            INSERT INTO data_service_admin_session
              (user_id, token_hash, expires_at, client_ip, user_agent)
            VALUES (?, ?, ?, ?, ?)
            """, userId, tokenHash, Timestamp.from(expiresAt), clientIp, truncate(userAgent, 500));
    }

    public void touchSession(String tokenHash) {
        jdbcTemplate.update("""
            UPDATE data_service_admin_session
            SET last_seen_at = CURRENT_TIMESTAMP
            WHERE token_hash = ? AND last_seen_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 MINUTE)
            """, tokenHash);
    }

    public void revokeSession(String tokenHash) {
        jdbcTemplate.update("""
            UPDATE data_service_admin_session
            SET revoked_at = CURRENT_TIMESTAMP
            WHERE token_hash = ? AND revoked_at IS NULL
            """, tokenHash);
    }

    public void revokeAllSessions(long userId) {
        jdbcTemplate.update("""
            UPDATE data_service_admin_session
            SET revoked_at = CURRENT_TIMESTAMP
            WHERE user_id = ? AND revoked_at IS NULL
            """, userId);
    }

    public void deleteExpiredSessions() {
        jdbcTemplate.update("""
            DELETE FROM data_service_admin_session
            WHERE expires_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY)
               OR revoked_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 7 DAY)
            """);
    }

    public void auditLogin(Long userId, String username, boolean success, String reason, String clientIp) {
        jdbcTemplate.update("""
            INSERT INTO data_service_admin_login_audit
              (user_id, username, success, failure_reason, client_ip)
            VALUES (?, ?, ?, ?, ?)
            """, userId, username, success, reason, clientIp);
    }

    private AdminUserRecord withAuthorities(AdminUserRecord user) {
        Set<String> roles = new LinkedHashSet<>(jdbcTemplate.queryForList("""
            SELECT role.code
            FROM data_service_admin_user_role user_role
            JOIN data_service_admin_role role ON role.id = user_role.role_id
            WHERE user_role.user_id = ?
            ORDER BY role.code
            """, String.class, user.id()));
        Set<String> permissions = new LinkedHashSet<>(jdbcTemplate.queryForList("""
            SELECT DISTINCT permission.code
            FROM data_service_admin_user_role user_role
            JOIN data_service_admin_role_permission role_permission
              ON role_permission.role_id = user_role.role_id
            JOIN data_service_admin_permission permission
              ON permission.id = role_permission.permission_id
            WHERE user_role.user_id = ?
            ORDER BY permission.code
            """, String.class, user.id()));
        return new AdminUserRecord(
            user.id(), user.username(), user.passwordHash(), user.displayName(), user.status(),
            user.failedAttempts(), user.lockedUntil(), user.lastLoginAt(), roles, permissions
        );
    }

    private AdminUserRecord mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new AdminUserRecord(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("status"),
            rs.getInt("failed_attempts"),
            toInstant(rs.getTimestamp("locked_until")),
            toInstant(rs.getTimestamp("last_login_at")),
            Set.of(),
            Set.of()
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record RoleOption(String code, String name, String description) {
    }
}
