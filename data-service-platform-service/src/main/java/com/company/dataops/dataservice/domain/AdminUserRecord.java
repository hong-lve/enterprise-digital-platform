package com.company.dataops.dataservice.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Set;

public record AdminUserRecord(
    Long id,
    String username,
    @JsonIgnore String passwordHash,
    String displayName,
    String status,
    Integer failedAttempts,
    Instant lockedUntil,
    Instant lastLoginAt,
    Set<String> roles,
    Set<String> permissions
) {
}
