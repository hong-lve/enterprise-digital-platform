package com.company.dataops.console.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LoginRateLimiterTest {

    /** Reflectively rewinds the internal Window.start for `key` in the given map field, to test the 15-minute expiry branch without a real wait. */
    @SuppressWarnings("unchecked")
    private void rewindWindowStart(LoginRateLimiter limiter, String fieldName, String key, Instant newStart) throws Exception {
        Field mapField = LoginRateLimiter.class.getDeclaredField(fieldName);
        mapField.setAccessible(true);
        Map<String, Object> map = (Map<String, Object>) mapField.get(limiter);
        Object window = map.get(key);
        Field startField = window.getClass().getDeclaredField("start");
        startField.setAccessible(true);
        startField.set(window, newStart);
    }

    @Test
    void allowsLoginUnderThePerUsernameThreshold() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        assertDoesNotThrow(() -> limiter.assertNotLocked("alice", "10.0.0.1"));
    }

    @Test
    void locksOutAfterFiveFailuresForSameUsername() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> limiter.assertNotLocked("alice", "10.0.0.1"));
        assertDoesNotThrow(() -> exception.getStatusCode());
    }

    @Test
    void locksOutAfterTwentyFailuresFromSameIpAcrossDifferentUsernames() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 20; i++) {
            limiter.recordFailure("user" + i, "10.0.0.9");
        }
        assertThrows(ResponseStatusException.class, () -> limiter.assertNotLocked("someone-else", "10.0.0.9"));
    }

    @Test
    void recordSuccessClearsBothCounters() {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        limiter.recordSuccess("alice", "10.0.0.1");
        assertDoesNotThrow(() -> limiter.assertNotLocked("alice", "10.0.0.1"));
    }

    @Test
    void expiredWindowIsClearedOnCheckInsteadOfStayingLocked() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        // Confirm it's actually locked before rewinding, so this test can't
        // pass vacuously if the rewind step silently no-ops.
        assertThrows(ResponseStatusException.class, () -> limiter.assertNotLocked("alice", "10.0.0.1"));

        rewindWindowStart(limiter, "byUsername", "alice", Instant.now().minus(16, ChronoUnit.MINUTES));

        assertDoesNotThrow(() -> limiter.assertNotLocked("alice", "10.0.0.1"));
    }

    @Test
    void expiredWindowResetsCountOnNextFailureInsteadOfAccumulating() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter();
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0.1");
        }
        rewindWindowStart(limiter, "byUsername", "alice", Instant.now().minus(16, ChronoUnit.MINUTES));

        limiter.recordFailure("alice", "10.0.0.1"); // should reset count to 1, not accumulate to 6
        assertDoesNotThrow(() -> limiter.assertNotLocked("alice", "10.0.0.1"));
    }
}
