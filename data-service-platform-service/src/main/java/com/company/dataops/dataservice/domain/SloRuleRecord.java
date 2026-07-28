package com.company.dataops.dataservice.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record SloRuleRecord(
    Long id,
    Long apiId,
    String name,
    boolean enabled,
    int windowMinutes,
    int minRequests,
    BigDecimal minSuccessRate,
    long maxP95Ms,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {
}
