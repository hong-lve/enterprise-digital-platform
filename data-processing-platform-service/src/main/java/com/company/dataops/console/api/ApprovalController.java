package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.ChangeRequestEntity;
import com.company.dataops.console.mapper.ChangeRequestMapper;
import com.company.dataops.console.service.approval.ChangeApprovalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Maker-checker review queue backing ChangeApprovalService - see its javadoc for why this exists. */
@RestController
@RequestMapping("/system/approval-requests")
public class ApprovalController {
    private final ChangeRequestMapper changeRequestMapper;
    private final ChangeApprovalService changeApprovalService;

    public ApprovalController(ChangeRequestMapper changeRequestMapper, ChangeApprovalService changeApprovalService) {
        this.changeRequestMapper = changeRequestMapper;
        this.changeApprovalService = changeApprovalService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:approval:view')")
    public ApiResponse<PageResult<ChangeRequestEntity>> page(
        @RequestParam(defaultValue = "1") long current,
        @RequestParam(defaultValue = "20") long pageSize,
        @RequestParam(required = false) String status
    ) {
        Page<ChangeRequestEntity> page = changeRequestMapper.selectPage(Page.of(current, pageSize), new LambdaQueryWrapper<ChangeRequestEntity>()
            .eq(status != null && !status.isBlank(), ChangeRequestEntity::getStatus, status)
            .orderByDesc(ChangeRequestEntity::getId));
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('system:approval:handle')")
    public ApiResponse<ChangeRequestEntity> approve(@PathVariable Long id) {
        return ApiResponse.ok(changeApprovalService.approve(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('system:approval:handle')")
    public ApiResponse<ChangeRequestEntity> reject(@PathVariable Long id, @RequestBody RejectRequest request) {
        return ApiResponse.ok(changeApprovalService.reject(id, request.reason()));
    }

    public record RejectRequest(String reason) {
    }
}
