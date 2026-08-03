package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record RolloutHealthSnapshot(
    Long requestCount,
    Long successCount,
    Long errorCount,
    Double successRate,
    Double errorRate,
    Double averageElapsedMs,
    Long p95ElapsedMs,
    Long p99ElapsedMs,
    Instant windowStartedAt
) {
}
