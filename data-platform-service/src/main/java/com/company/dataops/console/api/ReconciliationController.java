package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.ReconciliationCheckEntity;
import com.company.dataops.console.mapper.ReconciliationCheckMapper;
import com.company.dataops.console.service.DataReconciliationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages reconciliation_check rows (DataReconciliationScheduler runs every
 * enabled one every 2 minutes) plus an on-demand "立即执行" - registering a
 * check here is a one-time setup step (source/target table pairing), same
 * shape as registering a CdcSourceEntity or DataSourceEntity elsewhere in
 * this platform.
 */
@RestController
@RequestMapping("/realtime/reconciliation")
public class ReconciliationController {
    private final ReconciliationCheckMapper reconciliationCheckMapper;
    private final DataReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationCheckMapper reconciliationCheckMapper, DataReconciliationService reconciliationService) {
        this.reconciliationCheckMapper = reconciliationCheckMapper;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:reconciliation:view')")
    public ApiResponse<PageResult<ReconciliationCheckEntity>> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long pageSize) {
        Page<ReconciliationCheckEntity> page = reconciliationCheckMapper.selectPage(Page.of(current, pageSize),
            new LambdaQueryWrapper<ReconciliationCheckEntity>().orderByDesc(ReconciliationCheckEntity::getId));
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('realtime:reconciliation:create')")
    public ApiResponse<ReconciliationCheckEntity> create(@Valid @RequestBody ReconciliationCheckEntity request) {
        request.setId(null);
        if (request.getEnabled() == null) {
            request.setEnabled(true);
        }
        if (request.getTolerance() == null) {
            request.setTolerance(0);
        }
        request.setLastState("OK");
        reconciliationCheckMapper.insert(request);
        return ApiResponse.ok(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:reconciliation:update')")
    public ApiResponse<ReconciliationCheckEntity> update(@PathVariable Long id, @Valid @RequestBody ReconciliationCheckEntity request) {
        ReconciliationCheckEntity existing = requireExisting(id);
        request.setId(id);
        request.setLastSourceCount(existing.getLastSourceCount());
        request.setLastTargetCount(existing.getLastTargetCount());
        request.setLastCheckedAt(existing.getLastCheckedAt());
        request.setLastState(existing.getLastState());
        request.setLastError(existing.getLastError());
        if (request.getEnabled() == null) {
            request.setEnabled(existing.getEnabled());
        }
        if (request.getTolerance() == null) {
            request.setTolerance(existing.getTolerance());
        }
        reconciliationCheckMapper.updateById(request);
        return ApiResponse.ok(request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:reconciliation:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        reconciliationCheckMapper.deleteById(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasAuthority('realtime:reconciliation:run')")
    public ApiResponse<ReconciliationCheckEntity> run(@PathVariable Long id) {
        ReconciliationCheckEntity check = requireExisting(id);
        return ApiResponse.ok(reconciliationService.runCheck(check));
    }

    private ReconciliationCheckEntity requireExisting(Long id) {
        ReconciliationCheckEntity check = reconciliationCheckMapper.selectById(id);
        if (check == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对账任务不存在");
        }
        return check;
    }
}
