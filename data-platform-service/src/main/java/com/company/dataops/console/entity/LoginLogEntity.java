package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_login_log")
public class LoginLogEntity {
    private Long id;
    private String username;
    private String ipAddress;
    private String userAgent;
    private String result;
    private String message;
    private LocalDateTime createdAt;
}
