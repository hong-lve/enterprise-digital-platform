package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_menu")
public class MenuEntity {
    private Long id;
    private Long parentId;
    @NotBlank(message = "菜单名称不能为空")
    private String title;
    private String path;
    private String component;
    private String icon;
    private String permission;
    @NotBlank(message = "菜单类型不能为空")
    private String type;
    private Integer sortOrder;
    private String visible;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
