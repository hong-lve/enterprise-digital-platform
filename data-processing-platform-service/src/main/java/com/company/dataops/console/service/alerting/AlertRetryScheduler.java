package com.company.dataops.console.service.alerting;

import com.company.dataops.console.entity.AlertRetryQueueEntity;
import com.company.dataops.console.service.WebhookAlertSender;
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

    public AlertRetryScheduler(AlertRetryQueueService alertRetryQueueService, WebhookAlertSender webhookAlertSender) {
        this.alertRetryQueueService = alertRetryQueueService;
        this.webhookAlertSender = webhookAlertSender;
    }

    @Scheduled(fixedDelay = 30000)
    public void retryDue() {
        List<AlertRetryQueueEntity> due = alertRetryQueueService.claimDue(100);
        for (AlertRetryQueueEntity entry : due) {
            boolean delivered = webhookAlertSender.send(entry.getTitle(), entry.getContent(), entry.getType(), entry.getLinkUrl());
            if (delivered) {
                alertRetryQueueService.recordSuccess(entry);
            } else {
                alertRetryQueueService.recordFailure(entry, "webhook 投递仍然失败");
            }
        }
    }
}
