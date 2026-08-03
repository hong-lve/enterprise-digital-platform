package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.AlertHistoryEntity;
import com.company.dataops.console.entity.MessageEntity;
import com.company.dataops.console.entity.MessageReceiverEntity;
import com.company.dataops.console.entity.UserEntity;
import com.company.dataops.console.mapper.AlertHistoryMapper;
import com.company.dataops.console.mapper.MessageMapper;
import com.company.dataops.console.mapper.MessageReceiverMapper;
import com.company.dataops.console.mapper.UserMapper;
import com.company.dataops.console.service.alerting.AlertRetryQueueService;
import com.company.dataops.console.service.alerting.AlertSilenceService;
import com.company.dataops.console.service.alerting.OnCallService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RealtimeAlertService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeAlertService.class);

    private final MessageMapper messageMapper;
    private final MessageReceiverMapper messageReceiverMapper;
    private final UserMapper userMapper;
    private final WebhookAlertSender webhookAlertSender;
    private final AlertHistoryMapper alertHistoryMapper;
    private final AlertSilenceService alertSilenceService;
    private final OnCallService onCallService;
    private final AlertRetryQueueService alertRetryQueueService;

    public RealtimeAlertService(
        MessageMapper messageMapper,
        MessageReceiverMapper messageReceiverMapper,
        UserMapper userMapper,
        WebhookAlertSender webhookAlertSender,
        AlertHistoryMapper alertHistoryMapper,
        AlertSilenceService alertSilenceService,
        OnCallService onCallService,
        AlertRetryQueueService alertRetryQueueService
    ) {
        this.messageMapper = messageMapper;
        this.messageReceiverMapper = messageReceiverMapper;
        this.userMapper = userMapper;
        this.webhookAlertSender = webhookAlertSender;
        this.alertHistoryMapper = alertHistoryMapper;
        this.alertSilenceService = alertSilenceService;
        this.onCallService = onCallService;
        this.alertRetryQueueService = alertRetryQueueService;
    }

    /**
     * Identifies which entity/dimension an alert transition belongs to, so
     * it can be persisted to alert_history - orthogonal to owner/title/
     * content/linkUrl, which only control how the notification is delivered.
     */
    public record AlertSubject(String entityType, Long entityId, String entityName, String dimension) {
    }

    public void notifyFailure(AlertSubject subject, String owner, String title, String content, String linkUrl) {
        send(subject, owner, title, content, "ALERT", linkUrl);
    }

    public void notifyRecovery(AlertSubject subject, String owner, String title, String linkUrl) {
        send(subject, owner, title, "已恢复正常", "RECOVERY", linkUrl);
    }

    /**
     * Broadcast variant of send()'s in-app-message half, for events with no
     * single natural "owner" (e.g. a PROD change request: every current
     * holder of the approval permission needs to see it, not one fixed
     * user) - one shared MessageEntity, one MessageReceiverEntity per
     * recipient. No AlertSubject/alert_history recording here: that table
     * models health-state transitions of a monitored entity, which a
     * one-off "please review this" event isn't.
     */
    public void notifyMultiple(List<String> owners, String title, String content, String type, String linkUrl) {
        if (!webhookAlertSender.send(title, content, type, linkUrl)) {
            alertRetryQueueService.enqueue(title, content, type, linkUrl);
        }
        if (owners == null || owners.isEmpty()) {
            return;
        }
        try {
            MessageEntity message = new MessageEntity();
            message.setTitle(title);
            message.setContent(content);
            message.setType(type);
            message.setLinkUrl(linkUrl);
            message.setSender("system");
            messageMapper.insert(message);

            for (String owner : owners) {
                MessageReceiverEntity receiver = new MessageReceiverEntity();
                receiver.setMessageId(message.getId());
                receiver.setReceiver(owner);
                receiver.setReadStatus("UNREAD");
                messageReceiverMapper.insert(receiver);
            }
        } catch (Exception exception) {
            LOGGER.warn("Failed to broadcast {} message to {} recipients: {}", type, owners.size(), exception.getMessage());
        }
    }

    /**
     * Never throws - alerting must not break the polling scheduler or the
     * start/stop request that calls it. Silently does nothing if the owner
     * is blank or isn't a real username, same as data-processing-platform-service's
     * TaskAlertService.
     */
    private void send(AlertSubject subject, String owner, String title, String content, String type, String linkUrl) {
        // Recorded unconditionally, even if silenced below - alert_history is
        // the audit timeline of what actually happened, not of who got
        // bothered about it.
        recordHistory(subject, type, content);
        if (alertSilenceService.isSilenced(subject.entityType(), subject.entityId())) {
            return;
        }
        // Webhook fires regardless of owner - a DingTalk group robot is a
        // shared ops channel, not tied to a specific sys_user, unlike the
        // in-app message below which needs a real user row to attach to.
        if (!webhookAlertSender.send(title, content, type, linkUrl)) {
            alertRetryQueueService.enqueue(title, content, type, linkUrl);
        }
        if (owner != null && !owner.isBlank()) {
            sendInAppMessage(owner, title, content, type, linkUrl);
        }
        // On-call gets paged for every genuine failure, not just ones on
        // their own resources - that's the point of being on call. Skipped
        // for RECOVERY (would double every recovery ping) and skipped if
        // they're already the owner just notified above (would be a dupe).
        if ("ALERT".equals(type)) {
            String onCall = onCallService.currentOnCall();
            if (onCall != null && !onCall.equals(owner)) {
                sendInAppMessage(onCall, title, content, type, linkUrl);
            }
        }
    }

    /** Never throws - silently does nothing if the receiver isn't a real user. */
    private void sendInAppMessage(String receiver, String title, String content, String type, String linkUrl) {
        try {
            UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, receiver));
            if (user == null) {
                return;
            }
            MessageEntity message = new MessageEntity();
            message.setTitle(title);
            message.setContent(content);
            message.setType(type);
            message.setLinkUrl(linkUrl);
            message.setSender("system");
            messageMapper.insert(message);

            MessageReceiverEntity messageReceiver = new MessageReceiverEntity();
            messageReceiver.setMessageId(message.getId());
            messageReceiver.setReceiver(receiver);
            messageReceiver.setReadStatus("UNREAD");
            messageReceiverMapper.insert(messageReceiver);
        } catch (Exception exception) {
            LOGGER.warn("Failed to send {} alert to {}: {}", type, receiver, exception.getMessage());
        }
    }

    private static final int MESSAGE_MAX_LENGTH = 1000;

    // Best-effort, like the in-app message above - a failure to record
    // history must never prevent the alert itself from being delivered.
    private void recordHistory(AlertSubject subject, String type, String content) {
        try {
            AlertHistoryEntity history = new AlertHistoryEntity();
            history.setEntityType(subject.entityType());
            history.setEntityId(subject.entityId());
            history.setEntityName(subject.entityName());
            history.setDimension(subject.dimension());
            history.setState("ALERT".equals(type) ? "ALERTING" : "OK");
            // content can be a full exception stack trace (thousands of
            // chars) - column is VARCHAR(1000), and with STRICT_TRANS_TABLES
            // enabled MySQL rejects an overlong value outright instead of
            // truncating it, silently dropping the whole history row.
            history.setMessage(content != null && content.length() > MESSAGE_MAX_LENGTH ? content.substring(0, MESSAGE_MAX_LENGTH) : content);
            alertHistoryMapper.insert(history);
        } catch (Exception exception) {
            LOGGER.warn("Failed to record alert history for {} {}: {}", subject.entityType(), subject.entityId(), exception.getMessage());
        }
    }
}
