package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record ContractTestRun(
    Long id,
    Long caseId,
    Long apiId,
    Integer versionNo,
    String status,
    Long elapsedMs,
    Integer rowCount,
    String failureMessage,
    String runBy,
    Instant runAt
) {
}
