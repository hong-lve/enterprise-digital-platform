package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_user_totp")
public class UserTotpEntity {
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    private String secretEncrypted;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
}
