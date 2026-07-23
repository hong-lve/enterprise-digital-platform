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

    public RealtimeAlertService(
        MessageMapper messageMapper,
        MessageReceiverMapper messageReceiverMapper,
        UserMapper userMapper,
        WebhookAlertSender webhookAlertSender,
        AlertHistoryMapper alertHistoryMapper
    ) {
        this.messageMapper = messageMapper;
        this.messageReceiverMapper = messageReceiverMapper;
        this.userMapper = userMapper;
        this.webhookAlertSender = webhookAlertSender;
        this.alertHistoryMapper = alertHistoryMapper;
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
     * Never throws - alerting must not break the polling scheduler or the
     * start/stop request that calls it. Silently does nothing if the owner
     * is blank or isn't a real username, same as data-platform-service's
     * TaskAlertService.
     */
    private void send(AlertSubject subject, String owner, String title, String content, String type, String linkUrl) {
        // Webhook fires regardless of owner - a DingTalk group robot is a
        // shared ops channel, not tied to a specific sys_user, unlike the
        // in-app message below which needs a real user row to attach to.
        webhookAlertSender.send(title, content, type, linkUrl);
        recordHistory(subject, type, content);
        if (owner == null || owner.isBlank()) {
            return;
        }
        try {
            UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, owner));
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

            MessageReceiverEntity receiver = new MessageReceiverEntity();
            receiver.setMessageId(message.getId());
            receiver.setReceiver(owner);
            receiver.setReadStatus("UNREAD");
            messageReceiverMapper.insert(receiver);
        } catch (Exception exception) {
            LOGGER.warn("Failed to send {} alert to {}: {}", type, owner, exception.getMessage());
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
