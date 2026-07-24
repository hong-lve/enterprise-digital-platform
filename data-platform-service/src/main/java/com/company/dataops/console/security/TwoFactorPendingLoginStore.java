package com.company.dataops.console.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Holds a password-verified-but-not-yet-2FA-verified login between POST
 * /auth/login (password check) and POST /auth/2fa/verify (code check) - a
 * Redis hash per token instead of an in-process ConcurrentHashMap, so this
 * survives being behind a load balancer (the /login and /2fa/verify calls
 * for one login attempt can land on two different instances).
 *
 * Only the username is stored, not a full Authentication/UserDetails object -
 * unlike the in-memory version this replaced, which stashed the exact
 * Authentication login() already built (avoiding a second permissions
 * lookup). AuthController's AuthenticationManager builds its
 * UsernamePasswordAuthenticationToken with a plain username principal and
 * authorities from LocalAuthorityService.permissionsFor() (see
 * SecurityConfig's authenticationManager bean) - nothing UserDetails-shaped
 * that would need its own Redis (de)serialization strategy. Re-deriving the
 * authorities at /2fa/verify time via that same permissionsFor() call is
 * both simpler to serialize (a handful of primitive fields) and, if
 * anything, more correct than caching a 5-minutes-stale snapshot - a role
 * change mid-login-flow takes effect immediately instead of surviving
 * through completeLogin().
 *
 * pendingSecret is non-null only for a first-time enrollment (see
 * AuthController) - it's the newly generated TOTP secret, not yet persisted
 * to sys_user_totp, so an abandoned enrollment (user closes the tab after
 * seeing the QR code but never confirms it) never leaves a half-set-up row
 * behind; it only gets written to the database once verify() confirms the
 * user actually scanned it and can produce a valid code.
 */
@Component
public class TwoFactorPendingLoginStore {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_VERIFY_ATTEMPTS = 8;
    private static final String KEY_PREFIX = "2fa-pending:";

    private final StringRedisTemplate redisTemplate;

    public TwoFactorPendingLoginStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public record PendingLogin(String username, Long userId, String pendingSecret, int attempts) {
    }

    public String create(String username, Long userId, String pendingSecret) {
        String token = UUID.randomUUID().toString();
        String key = KEY_PREFIX + token;
        Map<String, String> fields = new HashMap<>();
        fields.put("username", username);
        fields.put("userId", String.valueOf(userId));
        fields.put("pendingSecret", pendingSecret == null ? "" : pendingSecret);
        fields.put("attempts", "0");
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, TTL);
        return token;
    }

    /** Null if the token doesn't exist, has expired, or has been used up by too many wrong codes - all treated as "start over from /auth/login" by the caller. */
    public PendingLogin get(String token) {
        String key = KEY_PREFIX + token;
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(key);
        if (fields.isEmpty()) {
            return null;
        }
        int attempts = Integer.parseInt((String) fields.get("attempts"));
        if (attempts >= MAX_VERIFY_ATTEMPTS) {
            redisTemplate.delete(key);
            return null;
        }
        String pendingSecret = (String) fields.get("pendingSecret");
        return new PendingLogin(
            (String) fields.get("username"),
            Long.valueOf((String) fields.get("userId")),
            pendingSecret == null || pendingSecret.isEmpty() ? null : pendingSecret,
            attempts
        );
    }

    public int recordFailedAttempt(String token) {
        String key = KEY_PREFIX + token;
        // Same null-if-absent contract the in-memory version had (no entry
        // means "already gone", not "create a fresh one to increment" -
        // HINCRBY on a since-expired key would otherwise silently create a
        // new hash with no TTL, a slow-leaking key that never expires).
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return MAX_VERIFY_ATTEMPTS;
        }
        Long attempts = redisTemplate.opsForHash().increment(key, "attempts", 1);
        return attempts == null ? MAX_VERIFY_ATTEMPTS : attempts.intValue();
    }

    public void consume(String token) {
        redisTemplate.delete(KEY_PREFIX + token);
    }
}
