package com.company.dataops.console.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.company.dataops.console.security.TwoFactorPendingLoginStore.PendingLogin;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class TwoFactorPendingLoginStoreTest {

    private static final Authentication AUTH = new UsernamePasswordAuthenticationToken("alice", "n/a");

    /** Reflectively backdates the final `expiresAt` field to test the TTL-expiry branch without a real 5-minute wait. */
    private void backdateExpiry(PendingLogin entry, Instant newExpiresAt) throws Exception {
        Field field = PendingLogin.class.getDeclaredField("expiresAt");
        field.setAccessible(true);
        field.set(entry, newExpiresAt);
    }

    @Test
    void createThenGetReturnsTheSameAuthenticationAndUserIdAndSecret() {
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore();
        String token = store.create(AUTH, 42L, "PENDINGSECRET");

        PendingLogin entry = store.get(token);
        assertNotNull(entry);
        assertSame(AUTH, entry.authentication);
        assertEquals(42L, entry.userId);
        assertEquals("PENDINGSECRET", entry.pendingSecret);
    }

    @Test
    void getReturnsNullForUnknownToken() {
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore();
        assertNull(store.get("no-such-token"));
    }

    @Test
    void consumeRemovesTheEntry() {
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore();
        String token = store.create(AUTH, 1L, null);
        store.consume(token);
        assertNull(store.get(token));
    }

    @Test
    void recordFailedAttemptIncrementsAndEventuallyExhaustsTheEntry() {
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore();
        String token = store.create(AUTH, 1L, null);

        int lastCount = 0;
        for (int i = 0; i < 8; i++) {
            lastCount = store.recordFailedAttempt(token);
        }
        assertEquals(8, lastCount);
        // MAX_VERIFY_ATTEMPTS reached - get() should now treat it as gone and evict it.
        assertNull(store.get(token));
    }

    @Test
    void recordFailedAttemptOnMissingTokenReturnsMaxAttemptsSentinel() {
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore();
        assertEquals(8, store.recordFailedAttempt("no-such-token"));
    }

    @Test
    void getEvictsAndReturnsNullOnceTtlHasElapsed() throws Exception {
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore();
        String token = store.create(AUTH, 1L, null);

        PendingLogin entry = store.get(token);
        assertNotNull(entry, "should still be valid before backdating expiry");

        backdateExpiry(entry, Instant.now().minus(1, ChronoUnit.MINUTES));

        assertNull(store.get(token));
    }
}
