package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.List;

public record DataApiRecord(
    Long id,
    Long datasetId,
    String name,
    String description,
    String path,
    String method,
    String querySql,
    List<ApiParameter> parameters,
    String status,
    Integer version,
    String latestVersionStatus,
    Integer publishedVersion,
    Integer cacheTtlSeconds,
    Integer maxPageSize,
    Instant publishedAt,
    Instant createdAt,
    Instant updatedAt
) {
}
