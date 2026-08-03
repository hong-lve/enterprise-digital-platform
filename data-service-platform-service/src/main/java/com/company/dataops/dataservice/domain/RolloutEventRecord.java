package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.Map;

public record RolloutEventRecord(
    Long id,
    Long rolloutId,
    String eventType,
    Integer stageIndex,
    Integer percentage,
    String message,
    String actor,
    Map<String, Object> details,
    Instant occurredAt
) {
}
