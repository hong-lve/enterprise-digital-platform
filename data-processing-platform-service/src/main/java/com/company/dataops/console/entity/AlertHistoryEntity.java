package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_history")
public class AlertHistoryEntity {
    private Long id;
    private String entityType;
    private Long entityId;
    private String entityName;
    private String dimension;
    private String state;
    private String message;
    private LocalDateTime occurredAt;
}
