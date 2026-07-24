package com.company.dataops.console.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.dataops.console.security.TwoFactorPendingLoginStore.PendingLogin;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class TwoFactorPendingLoginStoreTest {

    /**
     * Minimal in-memory Redis double - just the hash-field/EXPIRE/DEL
     * behavior TwoFactorPendingLoginStore actually calls - so these tests
     * stay hermetic (no real Redis instance needed, matching this project's
     * existing "pure JUnit, no external infra" test convention) while still
     * exercising real key-expiry semantics via the explicit expireNow()
     * hook below, instead of reflecting into the in-memory version's
     * PendingLogin.expiresAt field the way this test used to.
     */
    private static final class FakeRedis {
        private final Map<String, Map<String, String>> hashes = new HashMap<>();
        private final Set<String> expired = new HashSet<>();

        StringRedisTemplate template() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
            when(template.opsForHash()).thenReturn(hashOps);

            doAnswer(inv -> {
                putAll(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(hashOps).putAll(anyString(), anyMap());
            when(hashOps.entries(anyString())).thenAnswer(inv -> entries(inv.getArgument(0)));
            when(hashOps.increment(anyString(), any(), anyLong())).thenAnswer(inv ->
                hashIncrement(inv.getArgument(0), (String) inv.getArgument(1), (long) inv.getArgument(2)));
            when(template.hasKey(anyString())).thenAnswer(inv -> hasKey(inv.getArgument(0)));
            when(template.expire(anyString(), any())).thenReturn(true);
            doAnswer(inv -> {
                expireNow(inv.getArgument(0));
                return true;
            }).when(template).delete(anyString());
            return template;
        }

        private synchronized void putAll(String key, Map<String, String> fields) {
            hashes.computeIfAbsent(key, k -> new HashMap<>()).putAll(fields);
            expired.remove(key);
        }

        private synchronized Map<Object, Object> entries(String key) {
            if (expired.contains(key)) {
                return Map.of();
            }
            Map<String, String> fields = hashes.get(key);
            return fields == null ? Map.of() : new HashMap<>(fields);
        }

        private synchronized Long hashIncrement(String key, String field, long delta) {
            Map<String, String> fields = hashes.computeIfAbsent(key, k -> new HashMap<>());
            long next = Long.parseLong(fields.getOrDefault(field, "0")) + delta;
            fields.put(field, String.valueOf(next));
            return next;
        }

        private synchronized boolean hasKey(String key) {
            return !expired.contains(key) && hashes.containsKey(key);
        }

        /** Simulates the key's Redis EXPIRE elapsing, without a real 5-minute wait. */
        void expireNow(String key) {
            expired.add(key);
        }
    }

    private String tokenPrefix(String token) {
        return "2fa-pending:" + token;
    }

    @Test
    void createThenGetReturnsTheSameUsernameAndUserIdAndSecret() {
        FakeRedis redis = new FakeRedis();
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore(redis.template());
        String token = store.create("alice", 42L, "PENDINGSECRET");

        PendingLogin entry = store.get(token);
        assertNotNull(entry);
        assertEquals("alice", entry.username());
        assertEquals(42L, entry.userId());
        assertEquals("PENDINGSECRET", entry.pendingSecret());
    }

    @Test
    void getReturnsNullForUnknownToken() {
        FakeRedis redis = new FakeRedis();
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore(redis.template());
        assertNull(store.get("no-such-token"));
    }

    @Test
    void consumeRemovesTheEntry() {
        FakeRedis redis = new FakeRedis();
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore(redis.template());
        String token = store.create("alice", 1L, null);
        store.consume(token);
        assertNull(store.get(token));
    }

    @Test
    void recordFailedAttemptIncrementsAndEventuallyExhaustsTheEntry() {
        FakeRedis redis = new FakeRedis();
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore(redis.template());
        String token = store.create("alice", 1L, null);

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
        FakeRedis redis = new FakeRedis();
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore(redis.template());
        assertEquals(8, store.recordFailedAttempt("no-such-token"));
    }

    @Test
    void getEvictsAndReturnsNullOnceTtlHasElapsed() {
        FakeRedis redis = new FakeRedis();
        TwoFactorPendingLoginStore store = new TwoFactorPendingLoginStore(redis.template());
        String token = store.create("alice", 1L, null);

        assertNotNull(store.get(token), "should still be valid before expiring");

        redis.expireNow(tokenPrefix(token));

        assertNull(store.get(token));
    }
}
