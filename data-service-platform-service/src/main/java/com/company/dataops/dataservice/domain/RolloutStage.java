package com.company.dataops.dataservice.domain;

public record RolloutStage(
    Integer percentage,
    Integer observationMinutes
) {
}
