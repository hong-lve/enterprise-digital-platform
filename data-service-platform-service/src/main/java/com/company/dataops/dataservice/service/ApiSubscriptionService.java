package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.ApiSubscriptionRecord;
import com.company.dataops.dataservice.repository.ApiSubscriptionRepository;
import com.company.dataops.dataservice.repository.ApplicationRepository;
import com.company.dataops.dataservice.repository.DataApiRepository;
import com.company.dataops.dataservice.repository.RequestSecurityRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiSubscriptionService {
    private final ApiSubscriptionRepository repository;
    private final ApplicationRepository applicationRepository;
    private final DataApiRepository apiRepository;
    private final RequestSecurityRepository securityRepository;
    private final NotificationService notificationService;

    public ApiSubscriptionService(
        ApiSubscriptionRepository repository,
        ApplicationRepository applicationRepository,
        DataApiRepository apiRepository,
        RequestSecurityRepository securityRepository,
        NotificationService notificationService
    ) {
        this.repository = repository;
        this.applicationRepository = applicationRepository;
        this.apiRepository = apiRepository;
        this.securityRepository = securityRepository;
        this.notificationService = notificationService;
    }

    public List<ApiSubscriptionRecord> list() {
        return repository.findAll();
    }

    public ApiSubscriptionRecord submit(
        long appId,
        long apiId,
        String reason,
        int qpsLimit,
        long dailyLimit,
        Instant validFrom,
        Instant validUntil,
        List<String> ipAllowlist,
        String actor
    ) {
        applicationRepository.findById(appId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Application not found"));
        var api = apiRepository.findById(apiId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "API not found"));
        if (!"PUBLISHED".equals(api.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only published APIs can be subscribed");
        }
        validateLimits(qpsLimit, dailyLimit, validFrom, validUntil);
        return repository.submit(
            appId, apiId, reason, qpsLimit, dailyLimit, validFrom, validUntil,
            IpAllowlistPolicy.normalize(ipAllowlist), actor
        );
    }

    @Transactional
    public ApiSubscriptionRecord review(
        long id,
        String action,
        int qpsLimit,
        long dailyLimit,
        Instant validFrom,
        Instant validUntil,
        List<String> ipAllowlist,
        String actor,
        String comment
    ) {
        ApiSubscriptionRecord request = require(id);
        if (!"PENDING".equals(request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subscription is no longer pending");
        }
        if (request.requestedBy().equals(actor)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Requester cannot review their own subscription");
        }
        String status = switch (action.toUpperCase()) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported review action");
        };
        validateLimits(qpsLimit, dailyLimit, validFrom, validUntil);
        repository.review(
            id, status, qpsLimit, dailyLimit, validFrom, validUntil,
            IpAllowlistPolicy.normalize(ipAllowlist), actor, comment
        );
        return require(id);
    }

    public ApiSubscriptionRecord suspend(long id, String actor, String comment) {
        repository.suspend(id, actor, comment);
        return require(id);
    }

    public RuntimeQuota authorize(long appId, long apiId, String clientIp) {
        ApiSubscriptionRecord subscription = repository.findForRuntime(appId, apiId)
            .orElseThrow(() -> forbidden("Application has no subscription for this API"));
        if (!"APPROVED".equals(subscription.status())) {
            throw forbidden("API subscription is not approved");
        }
        Instant now = Instant.now();
        if (subscription.validFrom() != null && now.isBefore(subscription.validFrom())) {
            throw forbidden("API subscription is not active yet");
        }
        if (subscription.validUntil() != null && !now.isBefore(subscription.validUntil())) {
            throw forbidden("API subscription has expired");
        }
        if (!IpAllowlistPolicy.allows(subscription.ipAllowlist(), clientIp)) {
            throw forbidden("Client IP is not in the subscription allowlist");
        }
        RequestSecurityRepository.RateLimitDecision qps = securityRepository.acquire(
            "subscription:" + subscription.id(),
            subscription.qpsLimit(),
            now.getEpochSecond()
        );
        if (!qps.allowed()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Subscription QPS quota exceeded");
        }
        RequestSecurityRepository.DailyLimitDecision daily = securityRepository.acquireDaily(
            subscription.id(),
            subscription.dailyLimit(),
            LocalDate.now(ZoneId.of("Asia/Shanghai"))
        );
        if (!daily.allowed()) {
            if (daily.current() == daily.limit() + 1) {
                notificationService.enqueueEvent(
                    "SUBSCRIPTION_QUOTA_EXCEEDED",
                    "API subscription daily quota exceeded",
                    subscription.appName() + " exceeded the daily quota for " + subscription.apiName(),
                    java.util.Map.of(
                        "subscriptionId", subscription.id(),
                        "appId", subscription.appId(),
                        "apiId", subscription.apiId(),
                        "dailyLimit", subscription.dailyLimit()
                    )
                );
            }
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Subscription daily quota exceeded");
        }
        return new RuntimeQuota(subscription.id(), qps.limit(), qps.remaining(), daily.limit(), daily.remaining());
    }

    private ApiSubscriptionRecord require(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
    }

    private void validateLimits(int qpsLimit, long dailyLimit, Instant validFrom, Instant validUntil) {
        if (qpsLimit < 1 || qpsLimit > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QPS limit must be between 1 and 10000");
        }
        if (dailyLimit < 1 || dailyLimit > 1_000_000_000L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Daily limit is out of range");
        }
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid-until must be after valid-from");
        }
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    public record RuntimeQuota(
        long subscriptionId,
        int qpsLimit,
        int qpsRemaining,
        long dailyLimit,
        long dailyRemaining
    ) {
    }
}
