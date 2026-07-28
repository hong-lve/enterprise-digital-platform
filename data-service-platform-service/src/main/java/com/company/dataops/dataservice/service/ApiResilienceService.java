package com.company.dataops.dataservice.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiResilienceService {
    private final ConcurrentHashMap<Long, ApiState> states = new ConcurrentHashMap<>();
    private final LongAdder concurrencyRejected = new LongAdder();
    private final LongAdder circuitRejected = new LongAdder();
    private final int maxConcurrent;
    private final int failureThreshold;
    private final Duration openDuration;

    public ApiResilienceService(
        @Value("${platform.data-service.resilience.max-concurrent-per-api:20}") int maxConcurrent,
        @Value("${platform.data-service.resilience.failure-threshold:5}") int failureThreshold,
        @Value("${platform.data-service.resilience.open-duration:30s}") Duration openDuration
    ) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration;
    }

    public <T> T execute(long apiId, Supplier<T> supplier) {
        ApiState state = states.computeIfAbsent(apiId, ignored -> new ApiState(maxConcurrent));
        boolean probe = state.beforeExecution();
        if (state.isOpen() && !probe) {
            circuitRejected.increment();
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "API 熔断保护中，请稍后重试"
            );
        }
        if (!state.bulkhead().tryAcquire()) {
            if (probe) {
                state.halfOpenProbe().set(false);
            }
            concurrencyRejected.increment();
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "API 当前并发请求过多，请稍后重试"
            );
        }
        state.active().incrementAndGet();
        try {
            T value = supplier.get();
            state.onSuccess();
            return value;
        } catch (RuntimeException exception) {
            state.onFailure(failureThreshold, openDuration);
            throw exception;
        } finally {
            state.active().decrementAndGet();
            state.bulkhead().release();
            if (probe) {
                state.halfOpenProbe().set(false);
            }
        }
    }

    public ResilienceMetrics metrics() {
        List<CircuitSnapshot> circuits = states.entrySet().stream()
            .map(entry -> entry.getValue().snapshot(entry.getKey()))
            .sorted(Comparator.comparingLong(CircuitSnapshot::apiId))
            .toList();
        return new ResilienceMetrics(
            concurrencyRejected.sum(),
            circuitRejected.sum(),
            circuits
        );
    }

    private static final class ApiState {
        private final Semaphore bulkhead;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicBoolean halfOpenProbe = new AtomicBoolean();
        private volatile Instant openUntil;

        private ApiState(int maxConcurrent) {
            this.bulkhead = new Semaphore(maxConcurrent);
        }

        private boolean beforeExecution() {
            Instant until = openUntil;
            if (until == null) {
                return false;
            }
            if (Instant.now().isBefore(until)) {
                return false;
            }
            return halfOpenProbe.compareAndSet(false, true);
        }

        private boolean isOpen() {
            Instant until = openUntil;
            return until != null && (
                Instant.now().isBefore(until) || halfOpenProbe.get()
            );
        }

        private void onSuccess() {
            consecutiveFailures.set(0);
            openUntil = null;
        }

        private void onFailure(int threshold, Duration duration) {
            if (consecutiveFailures.incrementAndGet() >= threshold) {
                openUntil = Instant.now().plus(duration);
            }
        }

        private CircuitSnapshot snapshot(long apiId) {
            Instant until = openUntil;
            String status = until == null
                ? "CLOSED"
                : (Instant.now().isBefore(until) ? "OPEN" : "HALF_OPEN");
            return new CircuitSnapshot(
                apiId,
                status,
                active.get(),
                consecutiveFailures.get(),
                until
            );
        }

        private Semaphore bulkhead() {
            return bulkhead;
        }

        private AtomicInteger active() {
            return active;
        }

        private AtomicBoolean halfOpenProbe() {
            return halfOpenProbe;
        }
    }

    public record CircuitSnapshot(
        long apiId,
        String status,
        int activeRequests,
        int consecutiveFailures,
        Instant openUntil
    ) {
    }

    public record ResilienceMetrics(
        long concurrencyRejected,
        long circuitRejected,
        List<CircuitSnapshot> circuits
    ) {
    }
}
