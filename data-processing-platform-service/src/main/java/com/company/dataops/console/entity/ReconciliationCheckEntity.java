package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("reconciliation_check")
public class ReconciliationCheckEntity {
    private Long id;
    @NotBlank(message = "名称不能为空")
    private String name;
    @NotNull(message = "请选择源数据源")
    private Long sourceDataSourceId;
    private String sourceDatabase;
    @NotBlank(message = "源表名不能为空")
    private String sourceTable;
    @NotNull(message = "请选择目标数据源")
    private Long targetDataSourceId;
    private String targetDatabase;
    // Table name for a JDBC target (ClickHouse/MySQL/Oracle/Doris); a Redis
    // key pattern (e.g. "test_orders_mysql_redis_sink:*") for a Redis
    // target, since Redis has no table concept - see
    // DataReconciliationService.countTarget().
    @NotBlank(message = "目标表名/Key 匹配规则不能为空")
    private String targetTable;
    private Integer tolerance;
    private Boolean enabled;
    // ROW_COUNT (default) or AGGREGATE - see DataReconciliationService. Only
    // AGGREGATE reads aggregateColumn; both types honor partitionColumn.
    private String checkType;
    private String aggregateColumn;
    // Optional - breaks the comparison into a per-partition-value GROUP BY
    // instead of one table-wide number, so a drift can be pinned to which
    // partition/day it's actually in rather than just "somewhere in this table".
    private String partitionColumn;
    private Double lastSourceAggregate;
    private Double lastTargetAggregate;
    private String partitionDriftSummary;
    private Long lastSourceCount;
    private Long lastTargetCount;
    private LocalDateTime lastCheckedAt;
    private String lastState;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
