package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record ApiRolloutRecord(
    Long id,
    Long apiId,
    Integer baselineVersionNo,
    Integer candidateVersionNo,
    Integer percentage,
    Boolean automated,
    List<RolloutStage> stages,
    Integer currentStageIndex,
    Instant stageStartedAt,
    Instant nextEvaluationAt,
    RolloutHealthPolicy healthPolicy,
    String failureAction,
    String pausedReason,
    String pausedBy,
    Instant pausedAt,
    Set<Long> applicationIds,
    List<String> ipRules,
    String status,
    String note,
    String startedBy,
    Instant startedAt,
    String updatedBy,
    Instant updatedAt,
    String finishedBy,
    Instant finishedAt
) {
}
