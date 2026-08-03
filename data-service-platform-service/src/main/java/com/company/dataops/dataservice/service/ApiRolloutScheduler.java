package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.repository.ApiRolloutRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ApiRolloutScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiRolloutScheduler.class);

    private final ApiRolloutRepository repository;
    private final ApiRolloutService rolloutService;
    private final String workerId = UUID.randomUUID().toString();

    public ApiRolloutScheduler(
        ApiRolloutRepository repository,
        ApiRolloutService rolloutService
    ) {
        this.repository = repository;
        this.rolloutService = rolloutService;
    }

    @Scheduled(
        initialDelayString = "${platform.data-service.canary.initial-delay-ms:15000}",
        fixedDelayString = "${platform.data-service.canary.evaluation-interval-ms:10000}"
    )
    public void evaluateDueRollouts() {
        repository.claimDue(workerId, 20, 120).forEach(rolloutId -> {
            try {
                rolloutService.evaluateAutomated(rolloutId, workerId);
            } catch (RuntimeException exception) {
                repository.releaseLock(rolloutId, workerId);
                LOGGER.error("Automated canary evaluation failed for rollout {}", rolloutId, exception);
            }
        });
    }
}
