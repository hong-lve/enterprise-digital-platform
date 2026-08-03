package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.List;

public record ContractReport(
    Long id,
    Long apiId,
    Integer versionNo,
    Integer baselineVersionNo,
    String severity,
    List<ContractFinding> findings,
    Instant generatedAt
) {
    public boolean breaking() {
        return "BREAKING".equals(severity);
    }
}
