package com.company.dataops.console.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.MessageEntity;
import com.company.dataops.console.entity.MessageReceiverEntity;
import com.company.dataops.console.mapper.MessageMapper;
import com.company.dataops.console.mapper.MessageReceiverMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every endpoint here is scoped by "whoever is logged in", with no
 * @PreAuthorize permission gate backing that up (any authenticated user can
 * call it) - so the ownership check inside markRead() is the entire
 * security boundary between one user's inbox and another's. That's the one
 * property most worth locking down here.
 */
class MessageControllerTest {
    private MessageMapper messageMapper;
    private MessageReceiverMapper messageReceiverMapper;
    private MessageController controller;

    // markRead()/markAllRead() build a real LambdaUpdateWrapper<MessageReceiverEntity>
    // - normally MyBatis-Plus populates the field-name-to-column lambda cache
    // for an entity the first time a real Spring context boots and scans
    // mappers, which never happens in this pure-JUnit (no Spring context)
    // test convention. Without this, the wrapper construction itself throws
    // "can not find lambda cache for this entity" before the code under test
    // ever runs.
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), MessageReceiverEntity.class);
    }

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        messageReceiverMapper = mock(MessageReceiverMapper.class);
        controller = new MessageController(messageMapper, messageReceiverMapper);
        authenticateAs("alice");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    private MessageReceiverEntity receiverRow(long id, String receiver, String readStatus) {
        MessageReceiverEntity row = new MessageReceiverEntity();
        row.setId(id);
        row.setReceiver(receiver);
        row.setReadStatus(readStatus);
        row.setMessageId(1L);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    @Test
    void markReadOnOwnMessageSucceeds() {
        when(messageReceiverMapper.selectById(1L)).thenReturn(receiverRow(1L, "alice", "UNREAD"));

        controller.markRead(1L);

        verify(messageReceiverMapper).update(eq(null), any());
    }

    @Test
    void markReadRefusesAMessageBelongingToAnotherUser() {
        when(messageReceiverMapper.selectById(1L)).thenReturn(receiverRow(1L, "bob", "UNREAD"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> controller.markRead(1L));

        assertEquals(404, exception.getStatusCode().value());
        // The whole point of the check is that it runs BEFORE any mutation -
        // a 404 that still flipped bob's message to READ would be worse than
        // the 404 alone.
        verify(messageReceiverMapper, never()).update(any(), any());
    }

    @Test
    void markReadOnAnUnknownIdIsNotFoundRatherThanNullPointer() {
        when(messageReceiverMapper.selectById(999L)).thenReturn(null);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> controller.markRead(999L));

        assertEquals(404, exception.getStatusCode().value());
        verify(messageReceiverMapper, never()).update(any(), any());
    }

    @Test
    void pageSkipsTheMessageLookupEntirelyWhenThereAreNoReceiverRows() {
        Page<MessageReceiverEntity> emptyPage = new Page<>(1, 20);
        emptyPage.setRecords(List.of());
        emptyPage.setTotal(0);
        when(messageReceiverMapper.selectPage(any(), any())).thenReturn(emptyPage);

        ApiResponse<PageResult<MessageController.MessageView>> response = controller.page(1, 20, null);

        assertTrue(response.data().records().isEmpty());
        verify(messageMapper, never()).selectBatchIds(any());
    }

    @Test
    void pageFallsBackToAPlaceholderTitleWhenTheUnderlyingMessageIsGone() {
        MessageReceiverEntity row = receiverRow(1L, "alice", "UNREAD");
        Page<MessageReceiverEntity> receiverPage = new Page<>(1, 20);
        receiverPage.setRecords(List.of(row));
        receiverPage.setTotal(1);
        when(messageReceiverMapper.selectPage(any(), any())).thenReturn(receiverPage);
        // The message row this receiver points at (id 1) no longer exists.
        when(messageMapper.selectBatchIds(List.of(1L))).thenReturn(List.of());

        ApiResponse<PageResult<MessageController.MessageView>> response = controller.page(1, 20, null);

        MessageController.MessageView view = response.data().records().get(0);
        assertEquals("(消息已被删除)", view.title());
        assertEquals("UNREAD", view.readStatus());
    }
}
