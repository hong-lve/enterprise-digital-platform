package com.company.dataops.dataservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** A crash-safe, Redis-backed concurrency semaphore shared by all replicas. */
@Component
public class DistributedConcurrencyStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedConcurrencyStore.class);
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        local active = redis.call('ZCARD', KEYS[1])
        if active >= tonumber(ARGV[2]) then return -active end
        redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
        redis.call('PEXPIRE', KEYS[1], ARGV[5])
        return active + 1
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String prefix;
    private final boolean enabled;
    private final Counter fallbackCounter;
    private final Counter rejectionCounter;

    public DistributedConcurrencyStore(
        ObjectProvider<StringRedisTemplate> redisTemplate,
        MeterRegistry meterRegistry,
        @Value("${platform.data-service.resilience.redis-prefix:data-service:circuit}") String prefix,
        @Value("${platform.data-service.resilience.redis-enabled:true}") boolean enabled
    ) {
        this.redisTemplate = redisTemplate.getIfAvailable();
        this.prefix = prefix;
        this.enabled = enabled;
        this.fallbackCounter = Counter.builder("data_service_guardrail_fallback")
            .tag("guardrail", "global_concurrency")
            .register(meterRegistry);
        this.rejectionCounter = Counter.builder("data_service_global_concurrency_rejections")
            .register(meterRegistry);
    }

    public Lease acquire(long apiId, int limit, Duration leaseDuration) {
        if (!enabled) {
            return Lease.fallback();
        }
        if (redisTemplate == null) {
            fallbackCounter.increment();
            return Lease.fallback();
        }
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long leaseMillis = Math.max(1000, leaseDuration.toMillis());
        try {
            Long result = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(key(apiId)),
                String.valueOf(now),
                String.valueOf(limit),
                String.valueOf(now + leaseMillis),
                token,
                String.valueOf(leaseMillis * 2)
            );
            if (result == null || result <= 0) {
                rejectionCounter.increment();
                return Lease.rejected(result == null ? limit : Math.abs(result.intValue()));
            }
            return Lease.acquired(key(apiId), token, result.intValue());
        } catch (RuntimeException exception) {
            fallbackCounter.increment();
            LOGGER.warn("Redis global concurrency guard unavailable; using local bulkhead: {}", exception.getMessage());
            return Lease.fallback();
        }
    }

    public void release(Lease lease) {
        if (!lease.acquired() || lease.localFallback() || redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(lease.key(), lease.token());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to release Redis concurrency lease {}; it will expire automatically", lease.token());
        }
    }

    private String key(long apiId) {
        return prefix + ":" + apiId + ":concurrency";
    }

    public record Lease(boolean acquired, boolean localFallback, String key, String token, int active) {
        static Lease acquired(String key, String token, int active) {
            return new Lease(true, false, key, token, active);
        }

        static Lease rejected(int active) {
            return new Lease(false, false, null, null, active);
        }

        static Lease fallback() {
            return new Lease(true, true, null, null, 0);
        }
    }
}
