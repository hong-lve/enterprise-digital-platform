package com.company.dataops.dataservice.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record DataSourceRecord(
    Long id,
    String name,
    String engineType,
    String host,
    Integer port,
    String databaseName,
    String username,
    @JsonIgnore String passwordCiphertext,
    Integer poolMinIdle,
    Integer poolMaxSize,
    Long connectionTimeoutMs,
    Integer queryTimeoutSeconds,
    String environment,
    String owner,
    String status,
    String lastTestStatus,
    String lastTestMessage,
    Instant lastTestAt,
    Instant createdAt,
    Instant updatedAt
) {
    @JsonProperty("passwordConfigured")
    public boolean passwordConfigured() {
        return passwordCiphertext != null && !passwordCiphertext.isBlank();
    }
}
