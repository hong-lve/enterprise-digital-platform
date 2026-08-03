package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record CallLogRecord(
    Long id,
    Long apiId,
    Integer routedVersionNo,
    Long rolloutId,
    String rolloutVariant,
    String requestId,
    String traceId,
    String appKey,
    String apiPath,
    String method,
    Integer statusCode,
    Long elapsedMs,
    Integer rowCount,
    boolean testCall,
    String clientIp,
    String errorMessage,
    Instant occurredAt
) {
}
