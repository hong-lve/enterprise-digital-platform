package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Suppresses webhook/in-app alert delivery for a window of time - see
 * AlertSilenceService.isSilenced(), the only class that reads these.
 * entityType null = global; entityType set + entityId null = every entity
 * of that type; both set = one specific entity.
 */
@Data
@TableName("alert_silence_window")
public class AlertSilenceWindowEntity {
    private Long id;
    private String entityType;
    private Long entityId;
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startsAt;
    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endsAt;
    private String reason;
    private String createdBy;
    private LocalDateTime createdAt;
}
