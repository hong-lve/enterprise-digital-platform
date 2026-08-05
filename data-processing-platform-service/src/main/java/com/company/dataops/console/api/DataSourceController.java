package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ActionResult;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.security.EnvironmentGuard;
import com.company.dataops.console.service.approval.ChangeApprovalService;
import com.company.dataops.console.service.datasource.DataSourceConnectionService;
import jakarta.validation.Valid;
import java.util.List;
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

@RestController
@RequestMapping("/realtime/data-sources")
public class DataSourceController {
    private final DataSourceMapper dataSourceMapper;
    private final DataSourceConnectionService connectionService;
    private final EnvironmentGuard environmentGuard;
    private final ChangeApprovalService changeApprovalService;

    public DataSourceController(DataSourceMapper dataSourceMapper, DataSourceConnectionService connectionService, EnvironmentGuard environmentGuard, ChangeApprovalService changeApprovalService) {
        this.dataSourceMapper = dataSourceMapper;
        this.connectionService = connectionService;
        this.environmentGuard = environmentGuard;
        this.changeApprovalService = changeApprovalService;
        changeApprovalService.register(ChangeApprovalService.ActionType.DATA_SOURCE_DELETE, this::applyDelete);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:datasource:view')")
    public ApiResponse<PageResult<DataSourceEntity>> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long pageSize, @RequestParam(required = false) String type) {
        Page<DataSourceEntity> page = dataSourceMapper.selectPage(Page.of(current, pageSize), new LambdaQueryWrapper<DataSourceEntity>()
            .eq(type != null && !type.isBlank(), DataSourceEntity::getType, type)
            .orderByDesc(DataSourceEntity::getId));
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:datasource:view')")
    public ApiResponse<DataSourceEntity> detail(@PathVariable Long id) {
        return ApiResponse.ok(requireDataSource(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('realtime:datasource:create')")
    public ApiResponse<DataSourceEntity> create(@Valid @RequestBody DataSourceEntity dataSource) {
        // password isn't @NotBlank on the entity (editing needs to allow
        // leaving it out to keep the existing one), so it's enforced here
        // instead, only for create - same convention as CdcSourceEntity.
        if (dataSource.getPassword() == null || dataSource.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不能为空");
        }
        if (dataSource.getEnvironment() == null || dataSource.getEnvironment().isBlank()) {
            dataSource.setEnvironment("DEV");
        }
        dataSource.setStatus("UNTESTED");
        dataSource.setLastTestError(null);
        dataSourceMapper.insert(dataSource);
        return ApiResponse.ok(dataSource);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:datasource:update')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody DataSourceEntity dataSource) {
        DataSourceEntity existing = requireDataSource(id);
        environmentGuard.requirePermissionForEnvironment(existing.getEnvironment());
        environmentGuard.requirePermissionForEnvironment(dataSource.getEnvironment());
        dataSource.setId(id);
        // Null password in the payload means "leave it unchanged" - MyBatis-
        // Plus's default NOT_NULL update strategy skips null fields, same
        // convention as CdcSourceController.update().
        dataSourceMapper.updateById(dataSource);
        // Connection details may have just changed, so the last test result
        // is no longer trustworthy - force it back to UNTESTED (a plain
        // updateById() above wouldn't clear a stale status/error since both
        // were left null to avoid stomping on them when they weren't part of
        // the edit).
        dataSourceMapper.update(null, new LambdaUpdateWrapper<DataSourceEntity>()
            .eq(DataSourceEntity::getId, id)
            .set(DataSourceEntity::getStatus, "UNTESTED")
            .set(DataSourceEntity::getLastTestError, null));
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:datasource:delete')")
    public ApiResponse<ActionResult> delete(@PathVariable Long id) {
        DataSourceEntity dataSource = requireDataSource(id);
        environmentGuard.requirePermissionForEnvironment(dataSource.getEnvironment());
        ChangeApprovalService.GateResult gate = changeApprovalService.gate(
            ChangeApprovalService.ActionType.DATA_SOURCE_DELETE, id, dataSource.getEnvironment(), "数据源: " + dataSource.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyDelete(id);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyDelete(Long id) {
        dataSourceMapper.deleteById(id);
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('realtime:datasource:test')")
    public ApiResponse<DataSourceEntity> test(@PathVariable Long id) {
        DataSourceEntity dataSource = requireDataSource(id);
        return ApiResponse.ok(connectionService.testConnection(dataSource));
    }

    @GetMapping("/{id}/databases")
    @PreAuthorize("hasAuthority('realtime:datasource:view')")
    public ApiResponse<List<String>> databases(@PathVariable Long id) {
        DataSourceEntity dataSource = requireDataSource(id);
        return ApiResponse.ok(connectionService.listDatabases(dataSource));
    }

    private DataSourceEntity requireDataSource(Long id) {
        DataSourceEntity dataSource = dataSourceMapper.selectById(id);
        if (dataSource == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据源不存在");
        }
        return dataSource;
    }
}
