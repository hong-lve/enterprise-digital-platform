package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record DatasetRecord(
    Long id,
    String name,
    String description,
    String sourceType,
    String sourceName,
    String connectionMode,
    Long connectionId,
    String tableName,
    String owner,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
}
