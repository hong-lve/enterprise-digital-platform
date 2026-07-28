package com.company.dataops.dataservice.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record AlertEventRecord(
    Long id,
    Long ruleId,
    Long apiId,
    String alertType,
    String status,
    BigDecimal observedValue,
    BigDecimal thresholdValue,
    int sampleCount,
    String message,
    String acknowledgedBy,
    Instant acknowledgedAt,
    Instant resolvedAt,
    Instant openedAt,
    Instant updatedAt
) {
}
