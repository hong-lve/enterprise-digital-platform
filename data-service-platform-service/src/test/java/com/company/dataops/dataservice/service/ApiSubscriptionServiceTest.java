package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.domain.ApiSubscriptionRecord;
import com.company.dataops.dataservice.repository.ApiSubscriptionRepository;
import com.company.dataops.dataservice.repository.ApplicationRepository;
import com.company.dataops.dataservice.repository.DataApiRepository;
import com.company.dataops.dataservice.repository.RequestSecurityRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ApiSubscriptionServiceTest {
    private ApiSubscriptionRepository repository;
    private RequestSecurityRepository securityRepository;
    private ApiSubscriptionService service;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        repository = mock(ApiSubscriptionRepository.class);
        securityRepository = mock(RequestSecurityRepository.class);
        notificationService = mock(NotificationService.class);
        service = new ApiSubscriptionService(
            repository,
            mock(ApplicationRepository.class),
            mock(DataApiRepository.class),
            securityRepository,
            notificationService
        );
    }

    @Test
    void appliesSubscriptionQpsAndDailyQuota() {
        ApiSubscriptionRecord subscription = subscription("APPROVED", "requester");
        when(repository.findForRuntime(7L, 9L)).thenReturn(Optional.of(subscription));
        when(securityRepository.acquire(eq("subscription:3"), eq(12), anyLong()))
            .thenReturn(new RequestSecurityRepository.RateLimitDecision(true, 12, 11));
        when(securityRepository.acquireDaily(eq(3L), eq(5000L), any(LocalDate.class)))
            .thenReturn(new RequestSecurityRepository.DailyLimitDecision(true, 5000, 4999, 1));

        ApiSubscriptionService.RuntimeQuota result = service.authorize(7L, 9L, "10.20.8.9");

        assertEquals(11, result.qpsRemaining());
        assertEquals(4999, result.dailyRemaining());
    }

    @Test
    void rejectsClientOutsideIpAllowlist() {
        when(repository.findForRuntime(7L, 9L))
            .thenReturn(Optional.of(subscription("APPROVED", "requester")));

        assertThrows(
            ResponseStatusException.class,
            () -> service.authorize(7L, 9L, "192.168.1.5")
        );
    }

    @Test
    void preventsSubscriptionSelfReview() {
        when(repository.findById(3L))
            .thenReturn(Optional.of(subscription("PENDING", "requester")));

        assertThrows(
            ResponseStatusException.class,
            () -> service.review(
                3L, "APPROVE", 12, 5000, null, null,
                List.of("10.20.0.0/16"), "requester", null
            )
        );
    }

    @Test
    void notifiesOnceWhenDailyQuotaIsFirstExceeded() {
        ApiSubscriptionRecord subscription = subscription("APPROVED", "requester");
        when(repository.findForRuntime(7L, 9L)).thenReturn(Optional.of(subscription));
        when(securityRepository.acquire(eq("subscription:3"), eq(12), anyLong()))
            .thenReturn(new RequestSecurityRepository.RateLimitDecision(true, 12, 0));
        when(securityRepository.acquireDaily(eq(3L), eq(5000L), any(LocalDate.class)))
            .thenReturn(new RequestSecurityRepository.DailyLimitDecision(false, 5000, 0, 5001));

        assertThrows(
            ResponseStatusException.class,
            () -> service.authorize(7L, 9L, "10.20.8.9")
        );
        verify(notificationService).enqueueEvent(
            eq("SUBSCRIPTION_QUOTA_EXCEEDED"),
            any(),
            any(),
            any()
        );
    }

    private ApiSubscriptionRecord subscription(String status, String requester) {
        Instant now = Instant.now();
        return new ApiSubscriptionRecord(
            3L, 7L, "portal", "portal_app", 9L, "orders", "/orders", "GET",
            status, "business use", 12, 5000L, 0L, now.minusSeconds(60),
            now.plusSeconds(3600), List.of("10.20.0.0/16"), requester, now,
            null, null, null, now, now
        );
    }
}
