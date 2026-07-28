package com.company.dataops.dataservice.domain;

import java.time.Instant;
import java.util.List;

public record ApplicationRecord(
    Long id,
    String appKey,
    String name,
    String description,
    String status,
    Integer qpsLimit,
    Integer secretVersion,
    Instant lastRotatedAt,
    Instant createdAt,
    Instant updatedAt,
    List<Long> authorizedApiIds
) {
}
