package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

/** One explicit on-call shift - see OnCallService, the only class that reads these. */
@Data
@TableName("on_call_schedule")
public class OnCallScheduleEntity {
    private Long id;
    @NotBlank(message = "值班人不能为空")
    private String username;
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startsAt;
    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endsAt;
    private String note;
    private String createdBy;
    private LocalDateTime createdAt;
}
