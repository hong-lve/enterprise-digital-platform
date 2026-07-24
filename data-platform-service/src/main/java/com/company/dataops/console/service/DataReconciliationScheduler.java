package com.company.dataops.console.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-runs every enabled reconciliation_check row - unlike
 * CdcSourceStatusScheduler's 15s poll (a cheap Kafka Connect REST call), each
 * check here runs a real COUNT(*)/SCAN against a live source and target, so
 * this runs far less often: frequent enough to catch a real drift within a
 * few minutes, not so often it adds meaningful load to the very databases
 * this platform is already streaming CDC out of.
 */
@Component
public class DataReconciliationScheduler {
    private final DataReconciliationService reconciliationService;

    public DataReconciliationScheduler(DataReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(fixedDelay = 120000)
    public void poll() {
        reconciliationService.runAllEnabled();
    }
}
