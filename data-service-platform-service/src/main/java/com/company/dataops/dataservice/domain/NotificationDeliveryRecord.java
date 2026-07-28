package com.company.dataops.dataservice.domain;

import java.time.Instant;

public record NotificationDeliveryRecord(
    Long id,
    Long channelId,
    Long alertEventId,
    String eventType,
    String status,
    String payloadJson,
    int attempts,
    Instant nextAttemptAt,
    String lastError,
    Instant sentAt,
    Instant createdAt,
    Instant updatedAt
) {
}
