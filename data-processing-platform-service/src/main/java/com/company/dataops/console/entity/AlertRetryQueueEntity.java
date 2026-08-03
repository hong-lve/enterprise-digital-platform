package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** A webhook delivery pending retry - see AlertRetryQueueService/AlertRetryScheduler. */
@Data
@TableName("alert_retry_queue")
public class AlertRetryQueueEntity {
    private Long id;
    private String title;
    private String content;
    private String type;
    private String linkUrl;
    private Integer attempts;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptAt;
    private String status;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
