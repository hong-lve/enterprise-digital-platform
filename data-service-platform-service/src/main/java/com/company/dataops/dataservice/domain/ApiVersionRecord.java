package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.List;

public record ApiVersionRecord(
    Long id,
    Long apiId,
    Integer versionNo,
    Long datasetId,
    String name,
    String description,
    String path,
    String method,
    String querySql,
    List<ApiParameter> parameters,
    Integer cacheTtlSeconds,
    Integer maxPageSize,
    String status,
    String changeSummary,
    String createdBy,
    String submittedBy,
    Instant submittedAt,
    String reviewedBy,
    Instant reviewedAt,
    String reviewComment,
    Instant publishedAt,
    Long sourceVersionId,
    Instant createdAt
) {
    public DataApiRecord asApi(DataApiRecord api) {
        return new DataApiRecord(
            api.id(),
            datasetId,
            name,
            description,
            path,
            method,
            querySql,
            parameters,
            api.status(),
            versionNo,
            status,
            api.publishedVersion(),
            cacheTtlSeconds,
            maxPageSize,
            publishedAt,
            api.createdAt(),
            api.updatedAt()
        );
    }
}
