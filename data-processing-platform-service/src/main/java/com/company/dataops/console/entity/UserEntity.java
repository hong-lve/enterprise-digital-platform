package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_user")
public class UserEntity {
    private Long id;
    @NotBlank(message = "用户名不能为空")
    private String username;
    @NotBlank(message = "显示名称不能为空")
    private String displayName;
    @Email(message = "邮箱格式不正确")
    private String email;
    private String passwordHash;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
