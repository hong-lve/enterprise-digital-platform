package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_message")
public class MessageEntity {
    private Long id;
    @NotBlank(message = "消息标题不能为空")
    private String title;
    private String content;
    private String type;
    private String linkUrl;
    private String sender;
    private LocalDateTime createdAt;
}
