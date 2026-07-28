package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.AlertEventRecord;
import com.company.dataops.dataservice.domain.NotificationChannelRecord;
import com.company.dataops.dataservice.domain.NotificationDeliveryRecord;
import com.company.dataops.dataservice.repository.GovernanceRepository;
import com.company.dataops.dataservice.security.SecretCryptoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);
    private static final Set<String> CHANNEL_TYPES = Set.of("WEBHOOK", "DINGTALK", "WECHAT");

    private final GovernanceRepository repository;
    private final SecretCryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final boolean allowInsecureHttp;
    private final int maxAttempts;
    private final long retryBaseSeconds;

    public NotificationService(
        GovernanceRepository repository,
        SecretCryptoService cryptoService,
        ObjectMapper objectMapper,
        RestClient.Builder restClientBuilder,
        @Value("${platform.data-service.notification.allow-insecure-http:false}") boolean allowInsecureHttp,
        @Value("${platform.data-service.notification.max-attempts:5}") int maxAttempts,
        @Value("${platform.data-service.notification.retry-base-seconds:10}") long retryBaseSeconds
    ) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
        this.allowInsecureHttp = allowInsecureHttp;
        this.maxAttempts = maxAttempts;
        this.retryBaseSeconds = retryBaseSeconds;
    }

    public List<NotificationChannelRecord> channels() {
        return repository.findChannels();
    }

    public List<NotificationDeliveryRecord> deliveries(int limit) {
        return repository.findDeliveries(Math.min(Math.max(limit, 1), 500));
    }

    public NotificationChannelRecord create(
        String name,
        String channelType,
        String endpoint,
        boolean enabled,
        String actor
    ) {
        return repository.createChannel(
            required(name, "Channel name is required"),
            channelType(channelType),
            cryptoService.encrypt(validateEndpoint(endpoint)),
            enabled,
            actor
        );
    }

    public NotificationChannelRecord update(
        long id,
        String name,
        String channelType,
        String endpoint,
        boolean enabled
    ) {
        repository.findChannelCredential(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification channel not found"));
        String encrypted = endpoint == null || endpoint.isBlank()
            ? null
            : cryptoService.encrypt(validateEndpoint(endpoint));
        return repository.updateChannel(
            id,
            required(name, "Channel name is required"),
            channelType(channelType),
            encrypted,
            enabled
        );
    }

    public void enqueue(AlertEventRecord alert, String eventType) {
        repository.enqueueForEnabledChannels(alert.id(), eventType, payload(alert, eventType));
    }

    public void enqueueTest(long channelId) {
        repository.findChannelCredential(channelId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification channel not found"));
        repository.enqueueForChannel(channelId, "CHANNEL_TEST", json(Map.of(
            "eventType", "CHANNEL_TEST",
            "title", "Data service notification test",
            "content", "The notification channel is configured correctly.",
            "createdAt", Instant.now().toString()
        )));
    }

    public void enqueueEvent(String eventType, String title, String content, Map<String, Object> details) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("createdAt", Instant.now().toString());
        payload.putAll(details);
        repository.enqueueForEnabledChannels(null, eventType, json(payload));
    }

    @Scheduled(
        initialDelayString = "${platform.data-service.notification.initial-delay-ms:10000}",
        fixedDelayString = "${platform.data-service.notification.dispatch-interval-ms:10000}"
    )
    public void dispatchDue() {
        repository.claimDueDeliveries(50).forEach(this::safeDispatch);
    }

    private void safeDispatch(NotificationDeliveryRecord delivery) {
        try {
            GovernanceRepository.ChannelCredential channel = repository
                .findChannelCredential(delivery.channelId())
                .orElseThrow(() -> new IllegalStateException("Notification channel no longer exists"));
            if (!channel.enabled()) {
                throw new IllegalStateException("Notification channel is disabled");
            }
            JsonNode payload = objectMapper.readTree(delivery.payloadJson());
            Object body = switch (channel.channelType()) {
                case "DINGTALK", "WECHAT" -> Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", payload.path("content").asText())
                );
                default -> payload;
            };
            restClient.post()
                .uri(cryptoService.decrypt(channel.endpointCiphertext()))
                .body(body)
                .retrieve()
                .toBodilessEntity();
            repository.markDeliverySent(delivery.id());
        } catch (RuntimeException | JsonProcessingException exception) {
            int attempts = delivery.attempts() + 1;
            long delay = retryBaseSeconds * (1L << Math.min(attempts - 1, 10));
            repository.markDeliveryFailed(
                delivery.id(),
                attempts,
                maxAttempts,
                Instant.now().plusSeconds(delay),
                rootMessage(exception)
            );
            LOGGER.warn("Notification delivery {} failed on attempt {}", delivery.id(), attempts);
        }
    }

    private String payload(AlertEventRecord alert, String eventType) {
        String state = "ALERT_OPENED".equals(eventType) ? "opened" : "resolved";
        return json(Map.of(
            "eventType", eventType,
            "title", "Data service SLO alert " + state,
            "content", "API " + alert.apiId() + " " + alert.alertType() + " alert " + state
                + ": " + alert.message(),
            "alertId", alert.id(),
            "apiId", alert.apiId(),
            "alertType", alert.alertType(),
            "status", alert.status(),
            "createdAt", Instant.now().toString()
        ));
    }

    private String validateEndpoint(String endpoint) {
        String value = required(endpoint, "Webhook endpoint is required");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook endpoint is invalid");
        }
        boolean localHttp = "http".equalsIgnoreCase(uri.getScheme())
            && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if (uri.getHost() == null
            || (!"https".equalsIgnoreCase(uri.getScheme()) && !allowInsecureHttp && !localHttp)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook endpoint must use HTTPS");
        }
        return value;
    }

    private String channelType(String channelType) {
        String value = required(channelType, "Channel type is required").toUpperCase(Locale.ROOT);
        if (!CHANNEL_TYPES.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported notification channel type");
        }
        return value;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize notification payload", exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName() : message;
    }
}
