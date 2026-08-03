package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * A single-table data quality check (unlike ReconciliationCheckEntity, which
 * always compares a source against a target) - see DataQualityRuleService
 * for how ruleType dispatches to a different query per type.
 */
@Data
@TableName("data_quality_rule")
public class DataQualityRuleEntity {
    private Long id;
    @NotBlank(message = "名称不能为空")
    private String name;
    @NotNull(message = "请选择数据源")
    private Long dataSourceId;
    private String databaseName;
    @NotBlank(message = "表名不能为空")
    private String tableName;
    @NotBlank(message = "请选择规则类型")
    private String ruleType;
    @NotBlank(message = "请输入要检查的字段")
    private String columnName;
    private Double thresholdMin;
    private Double thresholdMax;
    private Boolean enabled;
    private String lastResult;
    private Double lastMetricValue;
    private Integer lastViolationCount;
    private LocalDateTime lastCheckedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
