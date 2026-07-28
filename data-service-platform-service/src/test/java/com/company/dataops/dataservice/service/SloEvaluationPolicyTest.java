package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.dataops.dataservice.domain.SloRuleRecord;
import com.company.dataops.dataservice.repository.SloRepository.ApiSloStatistics;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SloEvaluationPolicyTest {
    private final SloEvaluationPolicy policy = new SloEvaluationPolicy();

    @Test
    void skipsEvaluationWhenSamplesAreInsufficient() {
        SloEvaluationPolicy.Evaluation result = policy.evaluate(
            rule(),
            new ApiSloStatistics(9, new BigDecimal("80.000"), 5000)
        );

        assertFalse(result.sufficientSamples());
        assertTrue(result.breaches().isEmpty());
    }

    @Test
    void detectsSuccessRateAndLatencyBreaches() {
        SloEvaluationPolicy.Evaluation result = policy.evaluate(
            rule(),
            new ApiSloStatistics(100, new BigDecimal("98.500"), 1200)
        );

        assertTrue(result.sufficientSamples());
        assertEquals(2, result.breaches().size());
        assertEquals("SUCCESS_RATE", result.breaches().get(0).alertType());
        assertEquals("LATENCY_P95", result.breaches().get(1).alertType());
    }

    @Test
    void passesWhenBothObjectivesAreMet() {
        SloEvaluationPolicy.Evaluation result = policy.evaluate(
            rule(),
            new ApiSloStatistics(100, new BigDecimal("99.950"), 300)
        );

        assertTrue(result.sufficientSamples());
        assertTrue(result.breaches().isEmpty());
    }

    private SloRuleRecord rule() {
        return new SloRuleRecord(
            1L,
            2L,
            "核心查询 SLO",
            true,
            5,
            10,
            new BigDecimal("99.900"),
            500,
            "admin",
            Instant.now(),
            Instant.now()
        );
    }
}
