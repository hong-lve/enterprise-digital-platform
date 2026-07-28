package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.List;

public record ApiSubscriptionRecord(
    Long id,
    Long appId,
    String appName,
    String appKey,
    Long apiId,
    String apiName,
    String apiPath,
    String apiMethod,
    String status,
    String requestReason,
    Integer qpsLimit,
    Long dailyLimit,
    Long dailyUsed,
    Instant validFrom,
    Instant validUntil,
    List<String> ipAllowlist,
    String requestedBy,
    Instant requestedAt,
    String reviewedBy,
    Instant reviewedAt,
    String reviewComment,
    Instant createdAt,
    Instant updatedAt
) {
}
