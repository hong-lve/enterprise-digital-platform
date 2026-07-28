package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.SloRuleRecord;
import com.company.dataops.dataservice.repository.SloRepository.ApiSloStatistics;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SloEvaluationPolicy {
    public Evaluation evaluate(SloRuleRecord rule, ApiSloStatistics statistics) {
        if (statistics.sampleCount() < rule.minRequests()) {
            return new Evaluation(false, List.of());
        }
        List<Breach> breaches = new ArrayList<>();
        if (statistics.successRate().compareTo(rule.minSuccessRate()) < 0) {
            breaches.add(new Breach(
                "SUCCESS_RATE",
                statistics.successRate(),
                rule.minSuccessRate(),
                "API 成功率低于 SLO 目标"
            ));
        }
        if (statistics.p95Ms() > rule.maxP95Ms()) {
            breaches.add(new Breach(
                "LATENCY_P95",
                BigDecimal.valueOf(statistics.p95Ms()),
                BigDecimal.valueOf(rule.maxP95Ms()),
                "API P95 延迟超过 SLO 目标"
            ));
        }
        return new Evaluation(true, breaches);
    }

    public record Evaluation(boolean sufficientSamples, List<Breach> breaches) {
    }

    public record Breach(
        String alertType,
        BigDecimal observedValue,
        BigDecimal thresholdValue,
        String message
    ) {
    }
}
