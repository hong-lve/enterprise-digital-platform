package com.company.dataops.console.service.alerting;

import com.company.dataops.console.entity.AlertRetryQueueEntity;
import com.company.dataops.console.service.WebhookAlertSender;
import com.company.dataops.console.service.coordination.ClusterSingleton;
import com.company.dataops.console.service.monitoring.RealtimeMetrics;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries webhook deliveries AlertRetryQueueService.enqueue() recorded as
 * failed - see WebhookAlertSender.send()'s boolean return and
 * RealtimeAlertService's two call sites. 30s cadence matches the queue's own
 * shortest backoff tier, so a just-enqueued entry doesn't wait an extra
 * scheduler cycle beyond its own backoff on top of the tick interval.
 */
@Component
public class AlertRetryScheduler {
    private final AlertRetryQueueService alertRetryQueueService;
    private final WebhookAlertSender webhookAlertSender;
    private final RealtimeMetrics metrics;

    public AlertRetryScheduler(AlertRetryQueueService alertRetryQueueService, WebhookAlertSender webhookAlertSender,
                               RealtimeMetrics metrics) {
        this.alertRetryQueueService = alertRetryQueueService;
        this.webhookAlertSender = webhookAlertSender;
        this.metrics = metrics;
    }

    @ClusterSingleton(value = "alert-retry", lockAtMostSeconds = 300)
    @Scheduled(fixedDelay = 30000)
    public void retryDue() {
        List<AlertRetryQueueEntity> due = alertRetryQueueService.claimDue(100);
        for (AlertRetryQueueEntity entry : due) {
            boolean delivered = webhookAlertSender.send(entry.getTitle(), entry.getContent(), entry.getType(), entry.getLinkUrl());
            metrics.alertDelivery(delivered);
            if (delivered) {
                alertRetryQueueService.recordSuccess(entry);
            } else {
                alertRetryQueueService.recordFailure(entry, "webhook 投递仍然失败");
            }
        }
    }
}
