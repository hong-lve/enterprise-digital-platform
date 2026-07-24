package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("change_request")
public class ChangeRequestEntity {
    private Long id;
    private String actionType;
    private Long targetId;
    private String targetSummary;
    private String requester;
    private String status;
    private String approver;
    private String rejectReason;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
}
