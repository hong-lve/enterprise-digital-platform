package com.company.dataops.console.service.alerting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.AlertRetryQueueEntity;
import com.company.dataops.console.mapper.AlertRetryQueueMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tier 3 item 3 of the reliability roadmap ("告警重试队列") - WebhookAlertSender
 * previously gave up on a failed delivery with just a WARN log. Now a failed
 * send() enqueues here instead, and AlertRetryScheduler retries with backoff
 * (30s/120s/600s across 3 attempts) before giving up for good.
 */
@Component
public class AlertRetryQueueService {
    private static final int[] BACKOFF_SECONDS = {30, 120, 600};

    private final AlertRetryQueueMapper alertRetryQueueMapper;

    public AlertRetryQueueService(AlertRetryQueueMapper alertRetryQueueMapper) {
        this.alertRetryQueueMapper = alertRetryQueueMapper;
    }

    public void enqueue(String title, String content, String type, String linkUrl) {
        AlertRetryQueueEntity entry = new AlertRetryQueueEntity();
        entry.setTitle(title);
        entry.setContent(content);
        entry.setType(type);
        entry.setLinkUrl(linkUrl);
        entry.setAttempts(0);
        entry.setMaxAttempts(BACKOFF_SECONDS.length);
        entry.setNextAttemptAt(LocalDateTime.now().plusSeconds(BACKOFF_SECONDS[0]));
        entry.setStatus("PENDING");
        alertRetryQueueMapper.insert(entry);
    }

    public List<AlertRetryQueueEntity> due() {
        return alertRetryQueueMapper.selectList(new LambdaQueryWrapper<AlertRetryQueueEntity>()
            .eq(AlertRetryQueueEntity::getStatus, "PENDING")
            .le(AlertRetryQueueEntity::getNextAttemptAt, LocalDateTime.now()));
    }

    public void recordSuccess(AlertRetryQueueEntity entry) {
        alertRetryQueueMapper.update(null, new LambdaUpdateWrapper<AlertRetryQueueEntity>()
            .eq(AlertRetryQueueEntity::getId, entry.getId())
            .set(AlertRetryQueueEntity::getStatus, "SUCCEEDED"));
    }

    public void recordFailure(AlertRetryQueueEntity entry, String error) {
        int attempts = entry.getAttempts() + 1;
        LambdaUpdateWrapper<AlertRetryQueueEntity> update = new LambdaUpdateWrapper<AlertRetryQueueEntity>()
            .eq(AlertRetryQueueEntity::getId, entry.getId())
            .set(AlertRetryQueueEntity::getAttempts, attempts)
            .set(AlertRetryQueueEntity::getLastError, error);
        if (attempts >= entry.getMaxAttempts()) {
            update.set(AlertRetryQueueEntity::getStatus, "FAILED");
        } else {
            update.set(AlertRetryQueueEntity::getNextAttemptAt, LocalDateTime.now().plusSeconds(BACKOFF_SECONDS[attempts]));
        }
        alertRetryQueueMapper.update(null, update);
    }

    public List<AlertRetryQueueEntity> recent() {
        return alertRetryQueueMapper.selectList(new LambdaQueryWrapper<AlertRetryQueueEntity>()
            .orderByDesc(AlertRetryQueueEntity::getId)
            .last("LIMIT 100"));
    }
}
