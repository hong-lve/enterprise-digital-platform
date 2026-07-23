package com.company.dataops.console.security;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Holds a password-verified-but-not-yet-2FA-verified login in memory between
 * POST /auth/login (password check) and POST /auth/2fa/verify (code check) -
 * same single-instance-app assumption as LoginRateLimiter (a clustered
 * deployment would need a shared store instead). The Authentication object
 * from the first step is kept here rather than re-running the authentication
 * manager in verify() - password was already checked once, there's no reason
 * to ask for it again just to re-derive the same authorities.
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

    public static final class PendingLogin {
        public final Authentication authentication;
        public final Long userId;
        public final String pendingSecret;
        final Instant expiresAt = Instant.now().plus(TTL);
        final AtomicInteger attempts = new AtomicInteger();

        PendingLogin(Authentication authentication, Long userId, String pendingSecret) {
            this.authentication = authentication;
            this.userId = userId;
            this.pendingSecret = pendingSecret;
        }
    }

    private final ConcurrentHashMap<String, PendingLogin> pending = new ConcurrentHashMap<>();

    public String create(Authentication authentication, Long userId, String pendingSecret) {
        String token = UUID.randomUUID().toString();
        pending.put(token, new PendingLogin(authentication, userId, pendingSecret));
        return token;
    }

    /** Null if the token doesn't exist, has expired, or has been used up by too many wrong codes - all treated as "start over from /auth/login" by the caller. */
    public PendingLogin get(String token) {
        PendingLogin entry = pending.get(token);
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt) || entry.attempts.get() >= MAX_VERIFY_ATTEMPTS) {
            pending.remove(token);
            return null;
        }
        return entry;
    }

    public int recordFailedAttempt(String token) {
        PendingLogin entry = pending.get(token);
        return entry == null ? MAX_VERIFY_ATTEMPTS : entry.attempts.incrementAndGet();
    }

    public void consume(String token) {
        pending.remove(token);
    }
}
