package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.MessageEntity;
import com.company.dataops.console.entity.MessageReceiverEntity;
import com.company.dataops.console.mapper.MessageMapper;
import com.company.dataops.console.mapper.MessageReceiverMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * RealtimeAlertService has been writing to sys_message/sys_message_receiver
 * since the job/CDC failure-alert feature was built, but nothing ever read
 * it back - no controller, no frontend page. Every "owner" notification for
 * a failed CDC source or Flink job was a silently dead row. This is the
 * read side: each user's own inbox, scoped by their username as receiver.
 */
@RestController
@RequestMapping("/system/messages")
public class MessageController {
    private final MessageMapper messageMapper;
    private final MessageReceiverMapper messageReceiverMapper;

    public MessageController(MessageMapper messageMapper, MessageReceiverMapper messageReceiverMapper) {
        this.messageMapper = messageMapper;
        this.messageReceiverMapper = messageReceiverMapper;
    }

    public record MessageView(Long id, String title, String content, String type, String linkUrl, String sender, String readStatus, LocalDateTime createdAt) {
    }

    @GetMapping
    public ApiResponse<PageResult<MessageView>> page(
        @RequestParam(defaultValue = "1") long current,
        @RequestParam(defaultValue = "20") long pageSize,
        @RequestParam(required = false) String readStatus
    ) {
        String username = currentUsername();
        Page<MessageReceiverEntity> receiverPage = messageReceiverMapper.selectPage(Page.of(current, pageSize), new LambdaQueryWrapper<MessageReceiverEntity>()
            .eq(MessageReceiverEntity::getReceiver, username)
            .eq(readStatus != null && !readStatus.isBlank(), MessageReceiverEntity::getReadStatus, readStatus)
            .orderByDesc(MessageReceiverEntity::getId));
        List<MessageReceiverEntity> receivers = receiverPage.getRecords();
        if (receivers.isEmpty()) {
            return ApiResponse.ok(new PageResult<>(receiverPage.getTotal(), List.of()));
        }
        Map<Long, MessageEntity> messagesById = messageMapper.selectBatchIds(receivers.stream().map(MessageReceiverEntity::getMessageId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(MessageEntity::getId, Function.identity()));
        List<MessageView> views = receivers.stream()
            .map(receiver -> {
                MessageEntity message = messagesById.get(receiver.getMessageId());
                return new MessageView(
                    receiver.getId(),
                    message != null ? message.getTitle() : "(消息已被删除)",
                    message != null ? message.getContent() : null,
                    message != null ? message.getType() : null,
                    message != null ? message.getLinkUrl() : null,
                    message != null ? message.getSender() : null,
                    receiver.getReadStatus(),
                    receiver.getCreatedAt()
                );
            })
            .toList();
        return ApiResponse.ok(new PageResult<>(receiverPage.getTotal(), views));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(messageReceiverMapper.selectCount(new LambdaQueryWrapper<MessageReceiverEntity>()
            .eq(MessageReceiverEntity::getReceiver, currentUsername())
            .eq(MessageReceiverEntity::getReadStatus, "UNREAD")));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        MessageReceiverEntity receiver = messageReceiverMapper.selectById(id);
        if (receiver == null || !receiver.getReceiver().equals(currentUsername())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "消息不存在");
        }
        messageReceiverMapper.update(null, new LambdaUpdateWrapper<MessageReceiverEntity>()
            .eq(MessageReceiverEntity::getId, id)
            .set(MessageReceiverEntity::getReadStatus, "READ")
            .set(MessageReceiverEntity::getReadAt, LocalDateTime.now()));
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead() {
        messageReceiverMapper.update(null, new LambdaUpdateWrapper<MessageReceiverEntity>()
            .eq(MessageReceiverEntity::getReceiver, currentUsername())
            .eq(MessageReceiverEntity::getReadStatus, "UNREAD")
            .set(MessageReceiverEntity::getReadStatus, "READ")
            .set(MessageReceiverEntity::getReadAt, LocalDateTime.now()));
        return ApiResponse.ok();
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
