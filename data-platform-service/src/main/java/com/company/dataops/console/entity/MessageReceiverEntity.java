package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_message_receiver")
public class MessageReceiverEntity {
    private Long id;
    private Long messageId;
    private String receiver;
    private String readStatus;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
