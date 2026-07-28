package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record ChangeRequestRecord(
    Long id,
    String actionType,
    String targetType,
    Long targetId,
    String targetSummary,
    String environment,
    String payloadJson,
    String requester,
    String status,
    String approver,
    String decisionComment,
    Instant decidedAt,
    Instant createdAt,
    Instant updatedAt
) {
}
