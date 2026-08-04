package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** One offending row found by a rule execution. runId groups the append-only audit history. */
@Data
@TableName("data_quality_violation")
public class DataQualityViolationEntity {
    private Long id;
    private Long ruleId;
    private String runId;
    private String rowIdentifier;
    private String detail;
    private LocalDateTime detectedAt;
}
