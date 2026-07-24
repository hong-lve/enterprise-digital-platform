package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.AuditLogEntity;
import com.company.dataops.console.mapper.AuditLogMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view over what AuditLogInterceptor has recorded. */
@RestController
@RequestMapping("/system/audit-log")
public class AuditLogController {
    private final AuditLogMapper auditLogMapper;

    public AuditLogController(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:audit:view')")
    public ApiResponse<PageResult<AuditLogEntity>> page(
        @RequestParam(defaultValue = "1") long current,
        @RequestParam(defaultValue = "20") long pageSize,
        @RequestParam(required = false) String username,
        @RequestParam(required = false) String status
    ) {
        LambdaQueryWrapper<AuditLogEntity> query = new LambdaQueryWrapper<AuditLogEntity>()
            .eq(username != null && !username.isBlank(), AuditLogEntity::getUsername, username)
            .eq(status != null && !status.isBlank(), AuditLogEntity::getStatus, status)
            .orderByDesc(AuditLogEntity::getOccurredAt);
        Page<AuditLogEntity> page = auditLogMapper.selectPage(Page.of(current, pageSize), query);
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }
}
