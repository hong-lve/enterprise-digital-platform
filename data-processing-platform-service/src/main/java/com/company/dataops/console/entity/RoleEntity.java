package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_role")
public class RoleEntity {
    private Long id;
    @NotBlank(message = "角色标识不能为空")
    private String roleKey;
    @NotBlank(message = "角色名称不能为空")
    private String name;
    private String description;
    private String status;
    @NotBlank(message = "数据范围不能为空")
    private String dataScope;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
