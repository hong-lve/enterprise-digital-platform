package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class QueryCacheServiceTest {
    private QueryCacheService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<StringRedisTemplate> redisProvider = new EmptyRedisProvider();
        service = new QueryCacheService(
            redisProvider,
            new ObjectMapper().findAndRegisterModules(),
            100,
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            Duration.ofMillis(100),
            Duration.ofSeconds(1)
        );
    }

    @Test
    void cachesLocallyAndEvictsByApiEpoch() {
        AtomicInteger loads = new AtomicInteger();
        QueryCacheService.CacheRequest request = request(60);

        QueryCacheService.CacheOutcome first = service.getOrLoad(
            request,
            () -> rows(loads.incrementAndGet())
        );
        QueryCacheService.CacheOutcome second = service.getOrLoad(
            request,
            () -> rows(loads.incrementAndGet())
        );

        assertEquals("MISS", first.status());
        assertEquals("HIT", second.status());
        assertEquals(1, loads.get());

        service.evictApi(8L);
        QueryCacheService.CacheOutcome afterEviction = service.getOrLoad(
            request,
            () -> rows(loads.incrementAndGet())
        );
        assertEquals("MISS", afterEviction.status());
        assertEquals(2, loads.get());
    }

    @Test
    void servesStaleValueWhenRefreshFails() throws Exception {
        QueryCacheService.CacheRequest request = request(1);
        service.getOrLoad(request, () -> rows(1));
        Thread.sleep(1100);

        QueryCacheService.CacheOutcome fallback = service.getOrLoad(
            request,
            () -> {
                throw new IllegalStateException("database unavailable");
            }
        );

        assertEquals("STALE", fallback.status());
        assertEquals(1, fallback.rows().get(0).get("value"));
        assertEquals(1, service.metrics().staleFallbacks());
        assertFalse(service.metrics().redisAvailable());
    }

    private QueryCacheService.CacheRequest request(int ttlSeconds) {
        return new QueryCacheService.CacheRequest(
            8L,
            3,
            100L,
            ttlSeconds,
            "app-a",
            "127.0.0.1",
            1,
            20,
            Map.of("status", "ACTIVE")
        );
    }

    private List<Map<String, Object>> rows(int value) {
        return List.of(Map.of("value", value));
    }

    private static final class EmptyRedisProvider implements ObjectProvider<StringRedisTemplate> {
        @Override
        public StringRedisTemplate getObject(Object... args) {
            return null;
        }

        @Override
        public StringRedisTemplate getIfAvailable() {
            return null;
        }

        @Override
        public StringRedisTemplate getIfUnique() {
            return null;
        }

        @Override
        public StringRedisTemplate getObject() {
            return null;
        }
    }
}
