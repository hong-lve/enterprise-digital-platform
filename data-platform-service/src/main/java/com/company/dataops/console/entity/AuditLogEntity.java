package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("audit_log")
public class AuditLogEntity {
    private Long id;
    private String username;
    private String ipAddress;
    private String httpMethod;
    private String path;
    private String permission;
    private String status;
    private String errorMessage;
    private LocalDateTime occurredAt;
}
