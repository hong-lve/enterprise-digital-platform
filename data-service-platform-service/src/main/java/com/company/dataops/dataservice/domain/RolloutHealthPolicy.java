package com.company.dataops.dataservice.domain;

public record RolloutHealthPolicy(
    Integer minimumRequests,
    Double minimumSuccessRate,
    Double maximumErrorRate,
    Long maximumP95Ms,
    Long maximumP99Ms
) {
}
