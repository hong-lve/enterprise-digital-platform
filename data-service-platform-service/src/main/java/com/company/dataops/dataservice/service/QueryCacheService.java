package com.company.dataops.dataservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class QueryCacheService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QueryCacheService.class);
    private static final String PREFIX = "dsp:query:v1:";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        """
        if redis.call('get', KEYS[1]) == ARGV[1] then
          return redis.call('del', KEYS[1])
        end
        return 0
        """,
        Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Cache<String, LocalEntry> localCache;
    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> localEpochs = new ConcurrentHashMap<>();
    private final AtomicLong redisUnavailableUntil = new AtomicLong();
    private volatile String lastRedisError;
    private volatile boolean redisHealthy;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder staleFallbacks = new LongAdder();
    private final LongAdder bypasses = new LongAdder();
    private final Duration staleTtl;
    private final Duration lockTtl;
    private final Duration lockWait;
    private final long redisRetryMillis;
    private final String instanceId = UUID.randomUUID().toString();

    public QueryCacheService(
        ObjectProvider<StringRedisTemplate> redisTemplate,
        ObjectMapper objectMapper,
        @Value("${platform.data-service.cache.local-maximum-size:10000}") long localMaximumSize,
        @Value("${platform.data-service.cache.stale-ttl:5m}") Duration staleTtl,
        @Value("${platform.data-service.cache.lock-ttl:10s}") Duration lockTtl,
        @Value("${platform.data-service.cache.lock-wait:500ms}") Duration lockWait,
        @Value("${platform.data-service.cache.redis-retry:10s}") Duration redisRetry
    ) {
        this.redisTemplate = redisTemplate.getIfAvailable();
        this.redisHealthy = false;
        this.objectMapper = objectMapper;
        this.localCache = Caffeine.newBuilder()
            .maximumSize(Math.max(100, localMaximumSize))
            .build();
        this.staleTtl = staleTtl;
        this.lockTtl = lockTtl;
        this.lockWait = lockWait;
        this.redisRetryMillis = Math.max(1000, redisRetry.toMillis());
    }

    public CacheOutcome getOrLoad(CacheRequest request, Supplier<List<Map<String, Object>>> loader) {
        if (request.ttlSeconds() == null || request.ttlSeconds() <= 0) {
            bypasses.increment();
            return new CacheOutcome(loader.get(), "BYPASS", false);
        }

        String key = cacheKey(request);
        CachedRows cached = read(key, false);
        if (cached != null) {
            hits.increment();
            return new CacheOutcome(cached.rows(), "HIT", false);
        }

        ReentrantLock localLock = localLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        localLock.lock();
        String distributedLockKey = key + ":lock";
        String lockToken = null;
        try {
            cached = read(key, false);
            if (cached != null) {
                hits.increment();
                return new CacheOutcome(cached.rows(), "HIT", false);
            }

            lockToken = acquireDistributedLock(distributedLockKey);
            if (lockToken == null && redisAvailable()) {
                CachedRows waited = waitForValue(key);
                if (waited != null) {
                    hits.increment();
                    return new CacheOutcome(waited.rows(), "HIT", false);
                }
            }

            misses.increment();
            try {
                List<Map<String, Object>> rows = loader.get();
                write(key, rows, Duration.ofSeconds(request.ttlSeconds()));
                return new CacheOutcome(rows, "MISS", false);
            } catch (RuntimeException exception) {
                CachedRows stale = read(key, true);
                if (stale != null) {
                    staleFallbacks.increment();
                    return new CacheOutcome(stale.rows(), "STALE", true);
                }
                throw exception;
            }
        } finally {
            releaseDistributedLock(distributedLockKey, lockToken);
            localLock.unlock();
            if (!localLock.hasQueuedThreads()) {
                localLocks.remove(key, localLock);
            }
        }
    }

    public long evictApi(long apiId) {
        long epoch = localEpochs.computeIfAbsent(apiId, ignored -> new AtomicLong()).incrementAndGet();
        if (redisAvailable()) {
            try {
                Long distributed = redisTemplate.opsForValue().increment(epochKey(apiId));
                if (distributed != null) {
                    epoch = distributed;
                    localEpochs.get(apiId).set(distributed);
                }
                markRedisHealthy();
            } catch (RuntimeException exception) {
                markRedisUnavailable(exception);
            }
        }
        return epoch;
    }

    public CacheMetrics metrics() {
        long hitCount = hits.sum();
        long missCount = misses.sum();
        long cacheable = hitCount + missCount;
        double hitRate = cacheable == 0 ? 0 : (double) hitCount / cacheable;
        return new CacheMetrics(
            hitCount,
            missCount,
            staleFallbacks.sum(),
            bypasses.sum(),
            hitRate,
            redisHealthy,
            lastRedisError
        );
    }

    private String cacheKey(CacheRequest request) {
        long epoch = currentEpoch(request.apiId());
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("apiId", request.apiId());
        canonical.put("version", request.apiVersion());
        canonical.put("policyVersion", request.policyVersion());
        canonical.put("epoch", epoch);
        canonical.put("appKey", request.appKey());
        canonical.put("clientIp", request.clientIp());
        canonical.put("page", request.page());
        canonical.put("pageSize", request.pageSize());
        canonical.put("parameters", new TreeMap<>(request.parameters()));
        try {
            return PREFIX + request.apiId() + ":" + sha256(
                objectMapper.writeValueAsString(canonical)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("查询缓存键无法生成", exception);
        }
    }

    private CachedRows read(String key, boolean stale) {
        String target = stale ? key + ":stale" : key;
        LocalEntry local = localCache.getIfPresent(target);
        if (local != null) {
            if (local.expiresAt().isAfter(Instant.now())) {
                return local.value();
            }
            localCache.invalidate(target);
        }
        if (!redisAvailable()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(target);
            markRedisHealthy();
            if (json == null) {
                return null;
            }
            CachedRows value = objectMapper.readValue(json, new TypeReference<>() {});
            localCache.put(target, new LocalEntry(value, Instant.now().plus(Duration.ofSeconds(30))));
            return value;
        } catch (RuntimeException | JsonProcessingException exception) {
            markRedisUnavailable(exception);
            return null;
        }
    }

    private void write(String key, List<Map<String, Object>> rows, Duration ttl) {
        CachedRows value = new CachedRows(rows, Instant.now());
        Duration staleDuration = ttl.plus(staleTtl);
        localCache.put(key, new LocalEntry(value, Instant.now().plus(ttl)));
        localCache.put(key + ":stale", new LocalEntry(value, Instant.now().plus(staleDuration)));
        if (!redisAvailable()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttl);
            redisTemplate.opsForValue().set(key + ":stale", json, staleDuration);
            markRedisHealthy();
        } catch (RuntimeException | JsonProcessingException exception) {
            markRedisUnavailable(exception);
        }
    }

    private CachedRows waitForValue(String key) {
        long deadline = System.nanoTime() + lockWait.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
            CachedRows value = read(key, false);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String acquireDistributedLock(String key) {
        if (!redisAvailable()) {
            return null;
        }
        try {
            String token = instanceId + ":" + Thread.currentThread().getId();
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, lockTtl);
            markRedisHealthy();
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (RuntimeException exception) {
            markRedisUnavailable(exception);
            return null;
        }
    }

    private void releaseDistributedLock(String key, String token) {
        if (token == null || !redisAvailable()) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
            markRedisHealthy();
        } catch (RuntimeException exception) {
            markRedisUnavailable(exception);
        }
    }

    private long currentEpoch(long apiId) {
        AtomicLong local = localEpochs.computeIfAbsent(apiId, ignored -> new AtomicLong());
        if (!redisAvailable()) {
            return local.get();
        }
        try {
            String value = redisTemplate.opsForValue().get(epochKey(apiId));
            markRedisHealthy();
            if (value != null) {
                local.set(Long.parseLong(value));
            }
        } catch (RuntimeException exception) {
            markRedisUnavailable(exception);
        }
        return local.get();
    }

    private String epochKey(long apiId) {
        return PREFIX + apiId + ":epoch";
    }

    private boolean redisAvailable() {
        return redisTemplate != null && System.currentTimeMillis() >= redisUnavailableUntil.get();
    }

    private void markRedisUnavailable(Exception exception) {
        lastRedisError = rootMessage(exception);
        redisHealthy = false;
        redisUnavailableUntil.set(System.currentTimeMillis() + redisRetryMillis);
        LOGGER.warn("Redis query cache unavailable, using local cache: {}", lastRedisError);
    }

    private void markRedisHealthy() {
        redisHealthy = true;
        lastRedisError = null;
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank()
            ? cursor.getClass().getSimpleName()
            : message;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CacheRequest(
        long apiId,
        int apiVersion,
        long policyVersion,
        Integer ttlSeconds,
        String appKey,
        String clientIp,
        int page,
        int pageSize,
        Map<String, Object> parameters
    ) {
    }

    public record CacheOutcome(
        List<Map<String, Object>> rows,
        String status,
        boolean degraded
    ) {
    }

    public record CacheMetrics(
        long hits,
        long misses,
        long staleFallbacks,
        long bypasses,
        double hitRate,
        boolean redisAvailable,
        String lastRedisError
    ) {
    }

    private record CachedRows(List<Map<String, Object>> rows, Instant cachedAt) {
    }

    private record LocalEntry(CachedRows value, Instant expiresAt) {
    }
}
