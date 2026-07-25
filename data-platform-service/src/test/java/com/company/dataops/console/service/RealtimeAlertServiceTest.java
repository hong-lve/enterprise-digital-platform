package com.company.dataops.console.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.company.dataops.console.entity.MessageEntity;
import com.company.dataops.console.entity.MessageReceiverEntity;
import com.company.dataops.console.mapper.AlertHistoryMapper;
import com.company.dataops.console.mapper.MessageMapper;
import com.company.dataops.console.mapper.MessageReceiverMapper;
import com.company.dataops.console.mapper.UserMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * notifyMultiple() is the broadcast counterpart to send()'s single-owner
 * path: one shared MessageEntity row, one MessageReceiverEntity per
 * recipient, and (like send()) the webhook must fire even when there's no
 * one to notify in-app and a persistence failure must never escape as an
 * exception.
 */
class RealtimeAlertServiceTest {
    private MessageMapper messageMapper;
    private MessageReceiverMapper messageReceiverMapper;
    private WebhookAlertSender webhookAlertSender;
    private RealtimeAlertService service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        messageReceiverMapper = mock(MessageReceiverMapper.class);
        webhookAlertSender = mock(WebhookAlertSender.class);
        UserMapper userMapper = mock(UserMapper.class);
        AlertHistoryMapper alertHistoryMapper = mock(AlertHistoryMapper.class);

        AtomicLong nextId = new AtomicLong(100);
        doAnswer(invocation -> {
            MessageEntity message = invocation.getArgument(0);
            message.setId(nextId.getAndIncrement());
            return 1;
        }).when(messageMapper).insert(any(MessageEntity.class));

        service = new RealtimeAlertService(messageMapper, messageReceiverMapper, userMapper, webhookAlertSender, alertHistoryMapper);
    }

    @Test
    void createsOneSharedMessageAndOneReceiverRowPerRecipient() {
        service.notifyMultiple(List.of("alice", "bob", "carol"), "标题", "内容", "APPROVAL_PENDING", "http://x");

        verify(messageMapper, times(1)).insert(any(MessageEntity.class));

        ArgumentCaptor<MessageReceiverEntity> captor = ArgumentCaptor.forClass(MessageReceiverEntity.class);
        verify(messageReceiverMapper, times(3)).insert(captor.capture());
        List<MessageReceiverEntity> receivers = captor.getAllValues();
        assertEquals(List.of("alice", "bob", "carol"), receivers.stream().map(MessageReceiverEntity::getReceiver).toList());
        // All three receiver rows must point at the SAME message - not three
        // separate MessageEntity rows - or the recipients wouldn't really be
        // sharing one broadcast the way sys_message_receiver is modeled for.
        assertEquals(1, receivers.stream().map(MessageReceiverEntity::getMessageId).distinct().count());
        receivers.forEach(receiver -> assertEquals("UNREAD", receiver.getReadStatus()));
    }

    @Test
    void firesTheWebhookEvenWithNoInAppRecipients() {
        service.notifyMultiple(List.of(), "标题", "内容", "APPROVAL_PENDING", "http://x");

        verify(webhookAlertSender).send("标题", "内容", "APPROVAL_PENDING", "http://x");
        verify(messageMapper, never()).insert(any(MessageEntity.class));
        verify(messageReceiverMapper, never()).insert(any(MessageReceiverEntity.class));
    }

    @Test
    void firesTheWebhookEvenWithNullRecipientList() {
        service.notifyMultiple(null, "标题", "内容", "APPROVAL_PENDING", "http://x");

        verify(webhookAlertSender).send(eq("标题"), eq("内容"), eq("APPROVAL_PENDING"), eq("http://x"));
        verify(messageMapper, never()).insert(any(MessageEntity.class));
    }

    @Test
    void swallowsAPersistenceFailureInsteadOfPropagatingIt() {
        doThrow(new RuntimeException("db down")).when(messageMapper).insert(any(MessageEntity.class));

        assertDoesNotThrow(() -> service.notifyMultiple(List.of("alice"), "标题", "内容", "APPROVAL_PENDING", "http://x"));
        // The webhook is a separate delivery channel from the in-app message -
        // a DB failure on the message side shouldn't have stopped it either.
        verify(webhookAlertSender).send("标题", "内容", "APPROVAL_PENDING", "http://x");
    }
}
