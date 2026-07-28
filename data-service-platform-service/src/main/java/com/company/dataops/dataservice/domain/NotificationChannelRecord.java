package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record NotificationChannelRecord(
    Long id,
    String name,
    String channelType,
    boolean endpointConfigured,
    boolean enabled,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {
}
