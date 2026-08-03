package com.company.dataops.dataservice.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis-backed circuit state shared by every application replica. */
@Component
public class DistributedCircuitBreakerStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedCircuitBreakerStore.class);
    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>("""
        local failures = redis.call('INCR', KEYS[1])
        redis.call('EXPIRE', KEYS[1], ARGV[2])
        if failures >= tonumber(ARGV[1]) then
          redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
        end
        return failures
        """, Long.class);
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
        local openUntil = redis.call('GET', KEYS[1])
        if not openUntil then return 1 end
        if tonumber(openUntil) > tonumber(ARGV[1]) then return 0 end
        if redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[2]) then return 2 end
        return 0
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final String prefix;
    private final boolean enabled;
    private final Counter fallbackCounter;

    public DistributedCircuitBreakerStore(
        ObjectProvider<StringRedisTemplate> redisTemplate,
        MeterRegistry meterRegistry,
        @Value("${platform.data-service.resilience.redis-prefix:data-service:circuit}") String prefix,
        @Value("${platform.data-service.resilience.redis-enabled:true}") boolean enabled
    ) {
        this.redisTemplate = redisTemplate.getIfAvailable();
        this.prefix = prefix;
        this.enabled = enabled;
        this.fallbackCounter = Counter.builder("data_service_guardrail_fallback")
            .tag("guardrail", "circuit_breaker")
            .register(meterRegistry);
    }

    public Permit acquire(long apiId, Duration openDuration) {
        if (!enabled) {
            return Permit.LOCAL_FALLBACK;
        }
        if (redisTemplate == null) {
            fallbackCounter.increment();
            return Permit.LOCAL_FALLBACK;
        }
        try {
            long now = System.currentTimeMillis();
            Long result = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                List.of(openKey(apiId), probeKey(apiId)),
                String.valueOf(now),
                String.valueOf(Math.max(1000, openDuration.toMillis()))
            );
            return result != null && result == 2 ? Permit.PROBE
                : result != null && result == 1 ? Permit.ALLOW : Permit.REJECT;
        } catch (RuntimeException exception) {
            warn(exception);
            return Permit.LOCAL_FALLBACK;
        }
    }

    public void success(long apiId) {
        if (!available()) {
            return;
        }
        try {
            redisTemplate.delete(List.of(failureKey(apiId), openKey(apiId), probeKey(apiId)));
        } catch (RuntimeException exception) {
            warn(exception);
        }
    }

    public void cancelProbe(long apiId) {
        if (!available()) {
            return;
        }
        try {
            redisTemplate.delete(probeKey(apiId));
        } catch (RuntimeException exception) {
            warn(exception);
        }
    }

    public void failure(long apiId, int threshold, Duration openDuration) {
        if (!available()) {
            return;
        }
        try {
            long ttlSeconds = Math.max(60, openDuration.toSeconds() * 4);
            long openUntil = Instant.now().plus(openDuration).toEpochMilli();
            redisTemplate.execute(
                FAILURE_SCRIPT,
                List.of(failureKey(apiId), openKey(apiId)),
                String.valueOf(threshold),
                String.valueOf(ttlSeconds),
                String.valueOf(openUntil),
                String.valueOf(ttlSeconds * 1000)
            );
            redisTemplate.delete(probeKey(apiId));
        } catch (RuntimeException exception) {
            warn(exception);
        }
    }

    private boolean available() {
        return enabled && redisTemplate != null;
    }

    private String failureKey(long apiId) { return prefix + ":" + apiId + ":failures"; }
    private String openKey(long apiId) { return prefix + ":" + apiId + ":open-until"; }
    private String probeKey(long apiId) { return prefix + ":" + apiId + ":probe"; }

    private void warn(RuntimeException exception) {
        fallbackCounter.increment();
        LOGGER.warn("Redis circuit state unavailable; using local circuit state: {}", exception.getMessage());
    }

    public enum Permit { ALLOW, PROBE, REJECT, LOCAL_FALLBACK }
}
