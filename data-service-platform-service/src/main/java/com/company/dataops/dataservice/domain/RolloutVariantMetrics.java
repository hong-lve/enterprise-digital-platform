package com.company.dataops.dataservice.domain;

public record RolloutVariantMetrics(
    String variant,
    Integer versionNo,
    Long requestCount,
    Long successCount,
    Long errorCount,
    Double successRate,
    Double averageElapsedMs,
    Long maximumElapsedMs
) {
}
