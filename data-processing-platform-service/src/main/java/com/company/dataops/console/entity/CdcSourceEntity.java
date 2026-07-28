package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("cdc_source")
public class CdcSourceEntity {
    private Long id;
    @NotBlank(message = "名称不能为空")
    private String name;
    // References data_source.id - must point at a type='MYSQL' row, enforced
    // in CdcSourceController.create()/update() since this entity has no way
    // to see the referenced row's type itself. Replaces the old dbHost/
    // dbPort/dbUsername/dbPassword columns (see V15__cdc_source_data_source_id.sql).
    @NotNull(message = "请选择数据源")
    private Long dataSourceId;
    @NotBlank(message = "数据库名不能为空")
    private String databaseName;
    @NotBlank(message = "表清单不能为空")
    private String tableIncludeList;
    @NotBlank(message = "Topic 前缀不能为空")
    private String topicPrefix;
    private String connectorName;
    private String status;
    private String lastError;
    private String alertState;
    private Long lagSeconds;
    private String lagAlertState;
    // Optional - DEV/STAGING/PROD, server-defaults to DEV if blank (see
    // CdcSourceController.create()). Logical tag only, not physical
    // isolation - see V9__environment_field.sql. Gates start/stop/delete/
    // update via EnvironmentGuard when set to PROD.
    private String environment;
    private String owner;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
