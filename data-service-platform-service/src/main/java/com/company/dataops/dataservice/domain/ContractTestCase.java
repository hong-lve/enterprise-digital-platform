package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ContractTestCase(
    Long id,
    Long apiId,
    String name,
    boolean enabled,
    Map<String, Object> parameters,
    int page,
    int pageSize,
    List<ContractAssertion> assertions,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {
}
