package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_two_factor_setting")
public class TwoFactorSettingEntity {
    @TableId(value = "id", type = IdType.INPUT)
    private Integer id;
    private Boolean enabled;
    private LocalDateTime updatedAt;
}
