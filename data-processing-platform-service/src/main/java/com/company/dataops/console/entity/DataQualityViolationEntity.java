package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** One offending row (a duplicated PK, or an out-of-range value) found by a DataQualityRuleEntity's last run. */
@Data
@TableName("data_quality_violation")
public class DataQualityViolationEntity {
    private Long id;
    private Long ruleId;
    private String rowIdentifier;
    private String detail;
    private LocalDateTime detectedAt;
}
