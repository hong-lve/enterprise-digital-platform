package com.company.dataops.console.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unlike LoginRateLimiter (counts only failures), every call counts here -
 * the trickiest property to get right is that the limit fires exactly on
 * the (max+1)th call within the window, not one before or after, and that a
 * fresh window after expiry starts back at zero rather than continuing to
 * accumulate.
 */
class ActionRateLimiterTest {

    /** Same minimal in-memory Redis double style as LoginRateLimiterTest. */
    private static final class FakeRedis {
        private final Map<String, Long> values = new HashMap<>();
        private final Set<String> expired = new HashSet<>();

        StringRedisTemplate template() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenAnswer(inv -> increment(inv.getArgument(0)));
            when(template.expire(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
            return template;
        }

        private synchronized Long increment(String key) {
            if (expired.remove(key)) {
                values.remove(key);
            }
            long next = values.getOrDefault(key, 0L) + 1;
            values.put(key, next);
            return next;
        }

        void expireNow(String key) {
            expired.add(key);
        }
    }

    @Test
    void allowsCallsUpToAndIncludingTheLimit() {
        FakeRedis redis = new FakeRedis();
        ActionRateLimiter limiter = new ActionRateLimiter(redis.template());
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> limiter.assertWithinLimit("jar-build:alice", 10, Duration.ofMinutes(1)));
        }
    }

    @Test
    void rejectsExactlyTheCallAfterTheLimit() {
        FakeRedis redis = new FakeRedis();
        ActionRateLimiter limiter = new ActionRateLimiter(redis.template());
        for (int i = 0; i < 10; i++) {
            limiter.assertWithinLimit("jar-build:alice", 10, Duration.ofMinutes(1));
        }
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> limiter.assertWithinLimit("jar-build:alice", 10, Duration.ofMinutes(1)));
        org.junit.jupiter.api.Assertions.assertEquals(429, exception.getStatusCode().value());
    }

    @Test
    void tracksDifferentKeysIndependently() {
        FakeRedis redis = new FakeRedis();
        ActionRateLimiter limiter = new ActionRateLimiter(redis.template());
        for (int i = 0; i < 10; i++) {
            limiter.assertWithinLimit("jar-build:alice", 10, Duration.ofMinutes(1));
        }
        // bob's own bucket shouldn't be affected by alice hitting her limit.
        assertDoesNotThrow(() -> limiter.assertWithinLimit("jar-build:bob", 10, Duration.ofMinutes(1)));
    }

    @Test
    void aFreshWindowAfterExpiryStartsBackAtZero() {
        FakeRedis redis = new FakeRedis();
        ActionRateLimiter limiter = new ActionRateLimiter(redis.template());
        for (int i = 0; i < 10; i++) {
            limiter.assertWithinLimit("jar-build:alice", 10, Duration.ofMinutes(1));
        }
        assertThrows(ResponseStatusException.class, () -> limiter.assertWithinLimit("jar-build:alice", 10, Duration.ofMinutes(1)));

        redis.expireNow("jar-build:alice");

        // Should behave like a brand new window (count resets to 1), not
        // silently stay locked out or jump straight back over the limit.
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> limiter.assertWithinLimit("jar-build:alice", 10, Duration.ofMinutes(1)));
        }
        assertThrows(ResponseStatusException.class, () -> limiter.assertWithinLimit("jar-build:alice", 10, Duration.ofMinutes(1)));
    }
}
