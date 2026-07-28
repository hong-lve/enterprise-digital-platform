package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.SloRuleRecord;
import com.company.dataops.dataservice.repository.SloRepository;
import com.company.dataops.dataservice.repository.SloRepository.ApiSloStatistics;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SloEvaluationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SloEvaluationService.class);
    private static final Set<String> ALERT_TYPES = Set.of("SUCCESS_RATE", "LATENCY_P95");

    private final SloRepository repository;
    private final SloEvaluationPolicy policy;
    private final NotificationService notificationService;

    public SloEvaluationService(
        SloRepository repository,
        SloEvaluationPolicy policy,
        NotificationService notificationService
    ) {
        this.repository = repository;
        this.policy = policy;
        this.notificationService = notificationService;
    }

    @Scheduled(
        initialDelayString = "${platform.data-service.slo.initial-delay-ms:30000}",
        fixedDelayString = "${platform.data-service.slo.evaluation-interval-ms:60000}"
    )
    public void evaluateEnabledRules() {
        repository.findRules().stream()
            .filter(SloRuleRecord::enabled)
            .forEach(this::safeEvaluate);
    }

    public EvaluationResult evaluate(long ruleId) {
        SloRuleRecord rule = repository.findRule(ruleId).orElseThrow();
        return evaluate(rule);
    }

    private EvaluationResult evaluate(SloRuleRecord rule) {
        ApiSloStatistics statistics = repository.statistics(
            rule.apiId(),
            Instant.now().minus(rule.windowMinutes(), ChronoUnit.MINUTES)
        );
        SloEvaluationPolicy.Evaluation evaluation = policy.evaluate(rule, statistics);
        if (!evaluation.sufficientSamples()) {
            return new EvaluationResult(rule.id(), statistics, false, 0);
        }

        Set<String> breachedTypes = evaluation.breaches().stream()
            .map(SloEvaluationPolicy.Breach::alertType)
            .collect(java.util.stream.Collectors.toSet());
        for (SloEvaluationPolicy.Breach breach : evaluation.breaches()) {
            repository.openOrUpdateAlert(
                rule,
                breach.alertType(),
                breach.observedValue(),
                breach.thresholdValue(),
                statistics.sampleCount(),
                breach.message()
            ).ifPresent(alert -> notificationService.enqueue(alert, "ALERT_OPENED"));
        }
        ALERT_TYPES.stream()
            .filter(type -> !breachedTypes.contains(type))
            .forEach(type -> repository.resolveActiveAlert(rule.id(), type)
                .ifPresent(alert -> notificationService.enqueue(alert, "ALERT_RESOLVED")));
        return new EvaluationResult(
            rule.id(),
            statistics,
            true,
            evaluation.breaches().size()
        );
    }

    private void safeEvaluate(SloRuleRecord rule) {
        try {
            evaluate(rule);
        } catch (RuntimeException exception) {
            LOGGER.error("SLO evaluation failed for rule {}", rule.id(), exception);
        }
    }

    public record EvaluationResult(
        long ruleId,
        ApiSloStatistics statistics,
        boolean evaluated,
        int breachCount
    ) {
    }
}
