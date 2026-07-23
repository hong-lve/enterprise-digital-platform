package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.AlertHistoryEntity;
import com.company.dataops.console.mapper.AlertHistoryMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * alert_history grows without bound otherwise - every ALERTING/OK
 * transition for every CDC source/Flink job/SQL job gets a row (see
 * RealtimeAlertService.record()). Nothing reads back further than the
 * trend chart's 7-day cap (AlertHistoryController.trend()), so anything
 * older than the retention window is just dead weight.
 */
@Component
public class AlertHistoryRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlertHistoryRetentionScheduler.class);

    private final AlertHistoryMapper alertHistoryMapper;
    private final long retentionDays;

    public AlertHistoryRetentionScheduler(
        AlertHistoryMapper alertHistoryMapper,
        @Value("${platform.bigdata.alert-history-retention-days:30}") long retentionDays
    ) {
        this.alertHistoryMapper = alertHistoryMapper;
        this.retentionDays = retentionDays;
    }

    @Scheduled(initialDelay = 60_000L, fixedDelay = 24 * 60 * 60 * 1000L)
    public void purgeOldRecords() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = alertHistoryMapper.delete(new LambdaQueryWrapper<AlertHistoryEntity>()
            .lt(AlertHistoryEntity::getOccurredAt, cutoff));
        if (deleted > 0) {
            LOGGER.info("Purged {} alert_history row(s) older than {} ({} day retention)", deleted, cutoff, retentionDays);
        }
    }
}
