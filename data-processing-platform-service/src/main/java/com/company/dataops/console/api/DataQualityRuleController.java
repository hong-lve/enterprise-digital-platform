package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.DataQualityRuleEntity;
import com.company.dataops.console.entity.DataQualityViolationEntity;
import com.company.dataops.console.mapper.DataQualityRuleMapper;
import com.company.dataops.console.mapper.DataQualityViolationMapper;
import com.company.dataops.console.service.DataQualityRuleService;
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

/** Manages data_quality_rule rows (DataQualityScheduler runs every enabled one every 2 minutes) plus an on-demand "立即执行" and a violations list. */
@RestController
@RequestMapping("/realtime/data-quality")
public class DataQualityRuleController {
    private final DataQualityRuleMapper dataQualityRuleMapper;
    private final DataQualityViolationMapper dataQualityViolationMapper;
    private final DataQualityRuleService dataQualityRuleService;

    public DataQualityRuleController(DataQualityRuleMapper dataQualityRuleMapper, DataQualityViolationMapper dataQualityViolationMapper, DataQualityRuleService dataQualityRuleService) {
        this.dataQualityRuleMapper = dataQualityRuleMapper;
        this.dataQualityViolationMapper = dataQualityViolationMapper;
        this.dataQualityRuleService = dataQualityRuleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:data-quality:view')")
    public ApiResponse<PageResult<DataQualityRuleEntity>> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long pageSize) {
        Page<DataQualityRuleEntity> page = dataQualityRuleMapper.selectPage(Page.of(current, pageSize),
            new LambdaQueryWrapper<DataQualityRuleEntity>().orderByDesc(DataQualityRuleEntity::getId));
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('realtime:data-quality:create')")
    public ApiResponse<DataQualityRuleEntity> create(@Valid @RequestBody DataQualityRuleEntity request) {
        request.setId(null);
        if (request.getEnabled() == null) {
            request.setEnabled(true);
        }
        request.setLastResult("OK");
        dataQualityRuleMapper.insert(request);
        return ApiResponse.ok(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:data-quality:update')")
    public ApiResponse<DataQualityRuleEntity> update(@PathVariable Long id, @Valid @RequestBody DataQualityRuleEntity request) {
        DataQualityRuleEntity existing = requireExisting(id);
        request.setId(id);
        request.setLastResult(existing.getLastResult());
        request.setLastMetricValue(existing.getLastMetricValue());
        request.setLastViolationCount(existing.getLastViolationCount());
        request.setLastCheckedAt(existing.getLastCheckedAt());
        request.setLastError(existing.getLastError());
        if (request.getEnabled() == null) {
            request.setEnabled(existing.getEnabled());
        }
        dataQualityRuleMapper.updateById(request);
        return ApiResponse.ok(request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:data-quality:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        dataQualityRuleMapper.deleteById(id);
        dataQualityViolationMapper.delete(new LambdaQueryWrapper<DataQualityViolationEntity>().eq(DataQualityViolationEntity::getRuleId, id));
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasAuthority('realtime:data-quality:run')")
    public ApiResponse<DataQualityRuleEntity> run(@PathVariable Long id) {
        return ApiResponse.ok(dataQualityRuleService.runCheck(requireExisting(id)));
    }

    @GetMapping("/{id}/violations")
    @PreAuthorize("hasAuthority('realtime:data-quality:view')")
    public ApiResponse<List<DataQualityViolationEntity>> violations(@PathVariable Long id) {
        requireExisting(id);
        return ApiResponse.ok(dataQualityViolationMapper.selectList(new LambdaQueryWrapper<DataQualityViolationEntity>()
            .eq(DataQualityViolationEntity::getRuleId, id)
            .orderByDesc(DataQualityViolationEntity::getDetectedAt)
            .last("LIMIT 500")));
    }

    private DataQualityRuleEntity requireExisting(Long id) {
        DataQualityRuleEntity rule = dataQualityRuleMapper.selectById(id);
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据质量规则不存在");
        }
        return rule;
    }
}
