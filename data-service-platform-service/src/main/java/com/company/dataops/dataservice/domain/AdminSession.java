package com.company.dataops.dataservice.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

public record AdminSession(
    @JsonIgnore String token,
    Instant expiresAt,
    AdminUserRecord user
) {
}
