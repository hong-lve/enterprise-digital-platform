package com.company.dataops.dataservice.domain;

import java.util.List;
import java.util.Map;

public record ExecutionResult(
    String requestId,
    String traceId,
    Long apiId,
    String apiName,
    int page,
    int pageSize,
    int rowCount,
    long elapsedMs,
    String cacheStatus,
    boolean degraded,
    List<Map<String, Object>> rows
) {
}
