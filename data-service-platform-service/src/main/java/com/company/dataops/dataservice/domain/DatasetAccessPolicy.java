package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.List;

public record DatasetAccessPolicy(
    Long datasetId,
    String rowFilterSql,
    List<DatasetColumnPolicy> columns,
    String updatedBy,
    Instant updatedAt
) {
}
