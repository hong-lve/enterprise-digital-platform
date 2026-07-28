package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record OperationAuditRecord(
    Long id,
    String actor,
    String clientIp,
    String traceId,
    String httpMethod,
    String requestPath,
    String operation,
    String resourceId,
    String status,
    int statusCode,
    String errorMessage,
    String previousHash,
    String recordHash,
    Instant occurredAt
) {
}
