package com.company.dataops.console.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

class LoginRateLimiterTest {

    /**
     * Minimal in-memory Redis double - just the INCR/EXPIRE/GET/DEL behavior
     * LoginRateLimiter actually calls - so these tests stay hermetic (no
     * real Redis instance needed, matching this project's existing "pure
     * JUnit, no external infra" test convention) while still exercising
     * real key-expiry semantics via the explicit expireNow() hook below,
     * instead of reflecting into an internal Window/Instant field the way
     * the pre-Redis version of this test did.
     */
    private static final class FakeRedis {
        private final Map<String, Long> values = new HashMap<>();
        private final Set<String> expired = new HashSet<>();

        StringRedisTemplate template() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenAnswer(inv -> get(inv.getArgument(0)));
            when(valueOps.increment(anyString())).thenAnswer(inv -> increment(inv.getArgument(0)));
            when(template.expire(anyString(), any())).thenReturn(true);
            doAnswer(inv -> {
                expireNow(inv.getArgument(0));
                return true;
            }).when(template).delete(anyString());
            return template;
        }

        private synchronized String get(String key) {
            if (expired.contains(key)) {
                return null;
            }
            Long count = values.get(key);
            return count == null ? null : String.valueOf(count);
        }

        private synchronized Long increment(String key) {
            if (expired.remove(key)) {
                values.remove(key);
            }
            long next = values.getOrDefault(key, 0L) + 1;
            values.put(key, next);
            return next;
        }

        /** Simulates the key's Redis EXPIRE elapsing, without a real 15-minute wait. */
        void expireNow(String key) {
            expired.add(key);
        }
    }

    @Test
    void allowsLoginUnderThePerUsernameThreshold() {
        FakeRedis redis = new FakeRedis();
        LoginRateLimiter limiter = new LoginRateLimiter(redis.template());
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        assertDoesNotThrow(() -> limiter.assertNotLocked("alice", "10.0.0.1"));
    }

    @Test
    void locksOutAfterFiveFailuresForSameUsername() {
        FakeRedis redis = new FakeRedis();
        LoginRateLimiter limiter = new LoginRateLimiter(redis.template());
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> limiter.assertNotLocked("alice", "10.0.0.1"));
        assertDoesNotThrow(() -> exception.getStatusCode());
    }

    @Test
    void locksOutAfterTwentyFailuresFromSameIpAcrossDifferentUsernames() {
        FakeRedis redis = new FakeRedis();
        LoginRateLimiter limiter = new LoginRateLimiter(redis.template());
        for (int i = 0; i < 20; i++) {
            limiter.recordFailure("user" + i, "10.0.0.9");
        }
        assertThrows(ResponseStatusException.class, () -> limiter.assertNotLocked("someone-else", "10.0.0.9"));
    }

    @Test
    void recordSuccessClearsBothCounters() {
        FakeRedis redis = new FakeRedis();
        LoginRateLimiter limiter = new LoginRateLimiter(redis.template());
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        limiter.recordSuccess("alice", "10.0.0.1");
        assertDoesNotThrow(() -> limiter.assertNotLocked("alice", "10.0.0.1"));
    }

    @Test
    void expiredWindowIsClearedOnCheckInsteadOfStayingLocked() {
        FakeRedis redis = new FakeRedis();
        LoginRateLimiter limiter = new LoginRateLimiter(redis.template());
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        // Confirm it's actually locked before expiring, so this test can't
        // pass vacuously if the expiry step silently no-ops.
        assertThrows(ResponseStatusException.class, () -> limiter.assertNotLocked("alice", "10.0.0.1"));

        redis.expireNow("ratelimit:username:alice");

        assertDoesNotThrow(() -> limiter.assertNotLocked("alice", "10.0.0.1"));
    }

    @Test
    void expiredWindowResetsCountOnNextFailureInsteadOfAccumulating() {
        FakeRedis redis = new FakeRedis();
        LoginRateLimiter limiter = new LoginRateLimiter(redis.template());
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        redis.expireNow("ratelimit:username:alice");

        limiter.recordFailure("alice", "10.0.0.1"); // should reset count to 1, not accumulate to 6
        assertDoesNotThrow(() -> limiter.assertNotLocked("alice", "10.0.0.1"));
    }
}
