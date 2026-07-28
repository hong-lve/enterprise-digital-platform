package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record ApplicationSecretVersion(
    Long id,
    Long appId,
    Integer secretVersion,
    String status,
    Instant expiresAt,
    Instant lastUsedAt,
    String createdBy,
    Instant createdAt,
    String revokedBy,
    Instant revokedAt
) {
}
