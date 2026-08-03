package com.company.dataops.console.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs every enabled DataQualityRuleEntity - same 2-minute cadence as DataReconciliationScheduler, schema changes/data drift aren't near-real-time concerns. */
@Component
public class DataQualityScheduler {
    private final DataQualityRuleService dataQualityRuleService;

    public DataQualityScheduler(DataQualityRuleService dataQualityRuleService) {
        this.dataQualityRuleService = dataQualityRuleService;
    }

    @Scheduled(fixedDelay = 120000, initialDelay = 30000)
    public void runAll() {
        dataQualityRuleService.runAllEnabled();
    }
}
