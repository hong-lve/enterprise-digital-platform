package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** One rotation run's audit record - see EncryptionKeyRotationService, the only class that writes these. */
@Data
@TableName("encryption_key_rotation_event")
public class EncryptionKeyRotationEventEntity {
    private Long id;
    private String toVersion;
    private Integer dataSourceRowsReencrypted;
    private Integer totpRowsReencrypted;
    private String status;
    private String errorMessage;
    private String triggeredBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
