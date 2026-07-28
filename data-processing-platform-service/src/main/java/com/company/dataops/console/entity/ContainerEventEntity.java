package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("container_event")
public class ContainerEventEntity {
    private Long id;
    private String containerName;
    private String eventType;
    private String detail;
    private LocalDateTime occurredAt;
}
