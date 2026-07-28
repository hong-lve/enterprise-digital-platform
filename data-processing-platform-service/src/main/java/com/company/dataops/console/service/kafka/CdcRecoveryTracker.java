package com.company.dataops.console.service.kafka;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tracks bounded, spaced-out auto-recovery attempts per CDC source, mirroring
 * FlinkBackpressureInspector's style: purely in-memory, not persisted to
 * cdc_source - a service restart just means every source is optimistically
 * given a fresh attempt budget, which is arguably correct behavior anyway
 * (see CdcSourceStatusScheduler for how this is wired in).
 */
@Component
public class CdcRecoveryTracker {
    private final int maxAttempts;
    private final long delaySeconds;
    private final Map<Long, Attempt> attemptsBySourceId = new ConcurrentHashMap<>();

    public CdcRecoveryTracker(
        @Value("${platform.bigdata.cdc-recovery-max-attempts:3}") int maxAttempts,
        @Value("${platform.bigdata.cdc-recovery-delay-seconds:60}") long delaySeconds
    ) {
        this.maxAttempts = maxAttempts;
        this.delaySeconds = delaySeconds;
    }

    /** Whether it's worth calling KafkaConnectClient.restart() for this source right now. */
    public boolean shouldAttempt(Long sourceId) {
        Attempt attempt = attemptsBySourceId.get(sourceId);
        if (attempt == null) {
            return maxAttempts > 0;
        }
        if (attempt.count() >= maxAttempts) {
            return false;
        }
        return Instant.now().isAfter(attempt.lastAttemptAt().plusSeconds(delaySeconds));
    }

    /** Call once after actually issuing a best-effort restart call (regardless of whether the REST call itself succeeded - we can't synchronously confirm recovery either way). */
    public void recordAttempt(Long sourceId) {
        attemptsBySourceId.compute(sourceId, (id, previous) -> new Attempt(previous == null ? 1 : previous.count() + 1, Instant.now()));
    }

    public boolean exhausted(Long sourceId) {
        Attempt attempt = attemptsBySourceId.get(sourceId);
        return attempt != null && attempt.count() >= maxAttempts;
    }

    public int attemptCount(Long sourceId) {
        Attempt attempt = attemptsBySourceId.get(sourceId);
        return attempt == null ? 0 : attempt.count();
    }

    /** Clear tracking - call once a source is confirmed healthy again (self-heal or our restart worked) or is manually started/stopped, so the next independent failure gets a fresh budget. */
    public void forget(Long sourceId) {
        attemptsBySourceId.remove(sourceId);
    }

    private record Attempt(int count, Instant lastAttemptAt) {
    }
}
