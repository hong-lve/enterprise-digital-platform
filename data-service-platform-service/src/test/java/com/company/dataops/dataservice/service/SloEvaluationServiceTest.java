package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.domain.SloRuleRecord;
import com.company.dataops.dataservice.domain.AlertEventRecord;
import com.company.dataops.dataservice.repository.SloRepository;
import com.company.dataops.dataservice.repository.SloRepository.ApiSloStatistics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SloEvaluationServiceTest {
    private SloRepository repository;
    private SloEvaluationService service;
    private NotificationService notificationService;
    private SloRuleRecord rule;

    @BeforeEach
    void setUp() {
        repository = mock(SloRepository.class);
        notificationService = mock(NotificationService.class);
        service = new SloEvaluationService(repository, new SloEvaluationPolicy(), notificationService);
        rule = new SloRuleRecord(
            1L, 2L, "核心 API SLO", true, 5, 10,
            new BigDecimal("99.900"), 500, "admin", Instant.now(), Instant.now()
        );
        when(repository.findRule(1L)).thenReturn(Optional.of(rule));
    }

    @Test
    void opensBreachedAlertAndResolvesHealthyObjective() {
        when(repository.statistics(eq(2L), any())).thenReturn(
            new ApiSloStatistics(100, new BigDecimal("98.000"), 300)
        );
        AlertEventRecord opened = alert(10L, "SUCCESS_RATE", "OPEN");
        when(repository.openOrUpdateAlert(
            eq(rule), eq("SUCCESS_RATE"), any(), any(), eq(100), any()
        )).thenReturn(Optional.of(opened));

        SloEvaluationService.EvaluationResult result = service.evaluate(1L);

        assertEquals(1, result.breachCount());
        verify(repository).openOrUpdateAlert(
            eq(rule),
            eq("SUCCESS_RATE"),
            eq(new BigDecimal("98.000")),
            eq(new BigDecimal("99.900")),
            eq(100),
            any()
        );
        verify(repository).resolveActiveAlert(1L, "LATENCY_P95");
        verify(notificationService).enqueue(opened, "ALERT_OPENED");
    }

    @Test
    void resolvesBothAlertsAfterRecovery() {
        when(repository.statistics(eq(2L), any())).thenReturn(
            new ApiSloStatistics(100, new BigDecimal("100.000"), 100)
        );
        AlertEventRecord successRate = alert(11L, "SUCCESS_RATE", "RESOLVED");
        AlertEventRecord latency = alert(12L, "LATENCY_P95", "RESOLVED");
        when(repository.resolveActiveAlert(1L, "SUCCESS_RATE")).thenReturn(Optional.of(successRate));
        when(repository.resolveActiveAlert(1L, "LATENCY_P95")).thenReturn(Optional.of(latency));

        SloEvaluationService.EvaluationResult result = service.evaluate(1L);

        assertEquals(0, result.breachCount());
        verify(repository).resolveActiveAlert(1L, "SUCCESS_RATE");
        verify(repository).resolveActiveAlert(1L, "LATENCY_P95");
        verify(notificationService).enqueue(successRate, "ALERT_RESOLVED");
        verify(notificationService).enqueue(latency, "ALERT_RESOLVED");
    }

    private AlertEventRecord alert(long id, String type, String status) {
        return new AlertEventRecord(
            id, 1L, 2L, type, status, BigDecimal.ONE, BigDecimal.TEN,
            100, "message", null, null, null, Instant.now(), Instant.now()
        );
    }
}
