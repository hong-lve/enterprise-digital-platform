package com.company.dataops.console.service;

import com.company.dataops.console.mapper.HistoryRetentionMapper;
import com.company.dataops.console.service.coordination.ClusterSingleton;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HistoryRetentionScheduler {
    private static final int BATCH_SIZE = 5000;
    private static final int MAX_BATCHES_PER_RUN = 20;
    private final HistoryRetentionMapper mapper;
    private final int dataQualityDays;
    private final int checkpointDays;
    private final int recoveryDays;
    private final int auditDays;
    private final int operationDays;

    public HistoryRetentionScheduler(
        HistoryRetentionMapper mapper,
        @Value("${platform.retention.data-quality-days:90}") int dataQualityDays,
        @Value("${platform.retention.checkpoint-days:180}") int checkpointDays,
        @Value("${platform.retention.recovery-days:180}") int recoveryDays,
        @Value("${platform.retention.audit-days:365}") int auditDays,
        @Value("${platform.retention.operation-days:90}") int operationDays
    ) {
        this.mapper = mapper;
        this.dataQualityDays = dataQualityDays;
        this.checkpointDays = checkpointDays;
        this.recoveryDays = recoveryDays;
        this.auditDays = auditDays;
        this.operationDays = operationDays;
    }

    @ClusterSingleton(value = "history-retention", lockAtMostSeconds = 1800)
    @Scheduled(cron = "${platform.retention.cron:0 30 3 * * *}")
    public void purgeExpiredHistory() {
        LocalDateTime now = LocalDateTime.now();
        drain(() -> mapper.deleteDataQuality(now.minusDays(dataQualityDays), BATCH_SIZE));
        drain(() -> mapper.deleteCheckpoints(now.minusDays(checkpointDays), BATCH_SIZE));
        drain(() -> mapper.deleteRecoveryEvents(now.minusDays(recoveryDays), BATCH_SIZE));
        drain(() -> mapper.deleteAuditLogs(now.minusDays(auditDays), BATCH_SIZE));
        drain(() -> mapper.deleteJobOperations(now.minusDays(operationDays), BATCH_SIZE));
    }

    private void drain(IntSupplier deleteBatch) {
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            if (deleteBatch.getAsInt() < BATCH_SIZE) {
                return;
            }
        }
    }
}
