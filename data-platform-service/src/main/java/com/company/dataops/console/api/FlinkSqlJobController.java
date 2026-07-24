package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ActionResult;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.security.EnvironmentGuard;
import com.company.dataops.console.service.RealtimeAlertService;
import com.company.dataops.console.service.approval.ChangeApprovalService;
import com.company.dataops.console.service.flink.FlinkSqlGatewayClient;
import com.company.dataops.console.service.flink.FlinkSqlJobSubmissionService;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
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
 * Mirrors FlinkStreamJobController's structure (jar-based jobs), but the
 * "artifact" is a SQL script instead of a jar path - see
 * FlinkSqlJobSubmissionService for how that's submitted. stop()/
 * refreshStatus()/delete()'s stop-with-savepoint reuse
 * FlinkStreamSubmissionClient directly: those methods are job-id based and
 * don't care whether the job was originally submitted via jar or SQL.
 */
@RestController
@RequestMapping("/realtime/sql-jobs")
public class FlinkSqlJobController {
    private final FlinkSqlJobMapper flinkSqlJobMapper;
    private final FlinkSqlJobSubmissionService flinkSqlJobSubmissionService;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final RealtimeAlertService realtimeAlertService;
    private final EnvironmentGuard environmentGuard;
    private final ChangeApprovalService changeApprovalService;
    private final String frontendUrl;

    public FlinkSqlJobController(
        FlinkSqlJobMapper flinkSqlJobMapper,
        FlinkSqlJobSubmissionService flinkSqlJobSubmissionService,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        RealtimeAlertService realtimeAlertService,
        EnvironmentGuard environmentGuard,
        ChangeApprovalService changeApprovalService,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.flinkSqlJobMapper = flinkSqlJobMapper;
        this.flinkSqlJobSubmissionService = flinkSqlJobSubmissionService;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
        this.realtimeAlertService = realtimeAlertService;
        this.environmentGuard = environmentGuard;
        this.changeApprovalService = changeApprovalService;
        this.frontendUrl = frontendUrl;
        changeApprovalService.register(ChangeApprovalService.ActionType.FLINK_SQL_JOB_DELETE, this::applyDelete);
        changeApprovalService.register(ChangeApprovalService.ActionType.FLINK_SQL_JOB_STOP, this::applyStop);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:sql-job:view')")
    public ApiResponse<PageResult<FlinkSqlJobEntity>> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long pageSize) {
        Page<FlinkSqlJobEntity> page = flinkSqlJobMapper.selectPage(Page.of(current, pageSize), new LambdaQueryWrapper<FlinkSqlJobEntity>().orderByDesc(FlinkSqlJobEntity::getId));
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:sql-job:view')")
    public ApiResponse<FlinkSqlJobEntity> detail(@PathVariable Long id) {
        return ApiResponse.ok(requireJob(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('realtime:sql-job:create')")
    public ApiResponse<FlinkSqlJobEntity> create(@Valid @RequestBody FlinkSqlJobEntity job) {
        flinkSqlJobSubmissionService.assertJobShape(FlinkSqlGatewayClient.splitStatements(job.getSqlScript()));
        if (job.getEnvironment() == null || job.getEnvironment().isBlank()) {
            job.setEnvironment("DEV");
        }
        job.setStatus("DRAFT");
        job.setFlinkJobId(null);
        job.setSavepointPath(null);
        job.setLastError(null);
        flinkSqlJobMapper.insert(job);
        return ApiResponse.ok(job);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:sql-job:update')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody FlinkSqlJobEntity job) {
        flinkSqlJobSubmissionService.assertJobShape(FlinkSqlGatewayClient.splitStatements(job.getSqlScript()));
        FlinkSqlJobEntity existing = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(existing.getEnvironment());
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        job.setId(id);
        job.setStatus(null);
        job.setFlinkJobId(null);
        job.setSavepointPath(null);
        job.setLastError(null);
        flinkSqlJobMapper.updateById(job);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:sql-job:delete')")
    public ApiResponse<ActionResult> delete(@PathVariable Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        ChangeApprovalService.GateResult gate = changeApprovalService.gate(
            ChangeApprovalService.ActionType.FLINK_SQL_JOB_DELETE, id, job.getEnvironment(), "SQL 流作业: " + job.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyDelete(id);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyDelete(Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        if ("RUNNING".equals(job.getStatus()) && job.getFlinkJobId() != null) {
            try {
                flinkStreamSubmissionClient.stopWithSavepoint(job.getFlinkJobId());
            } catch (Exception ignored) {
                // best-effort: Flink cluster being unreachable shouldn't block removing the local row
            }
        }
        flinkSqlJobMapper.deleteById(id);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('realtime:sql-job:start')")
    public ApiResponse<FlinkSqlJobEntity> start(@PathVariable Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        if (job.getFlinkJobId() != null && flinkStreamSubmissionClient.isRunning(job.getFlinkJobId())) {
            // Already running on the cluster - see FlinkStreamSubmissionClient.isRunning()'s
            // own comment for why a fresh submit() here would be actively harmful.
            // Also self-heals a status that was wrongly clobbered to FAILED by
            // an earlier redundant start() attempt (the original bug this
            // whole check exists to prevent) - the instance was fine the
            // entire time, only the DB row's label was wrong.
            if (!"RUNNING".equals(job.getStatus())) {
                job.setStatus("RUNNING");
                job.setLastError(null);
                flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                    .eq(FlinkSqlJobEntity::getId, job.getId())
                    .set(FlinkSqlJobEntity::getStatus, "RUNNING")
                    .set(FlinkSqlJobEntity::getLastError, null));
            }
            return ApiResponse.ok(job);
        }
        try {
            String flinkJobId = flinkSqlJobSubmissionService.submit(job);
            job.setFlinkJobId(flinkJobId);
            job.setStatus("RUNNING");
            job.setLastError(null);
            if ("ALERTING".equals(job.getAlertState())) {
                realtimeAlertService.notifyRecovery(
                    new RealtimeAlertService.AlertSubject("SQL_JOB", job.getId(), job.getName(), "JOB_FAILURE"),
                    job.getOwner(),
                    "SQL 流作业已恢复：" + job.getName(),
                    frontendUrl + "/realtime/sql-jobs"
                );
                job.setAlertState("OK");
            }
        } catch (ResponseStatusException exception) {
            job.setStatus("FAILED");
            job.setLastError(exception.getReason());
        }
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, job.getId())
            .set(FlinkSqlJobEntity::getFlinkJobId, job.getFlinkJobId())
            .set(FlinkSqlJobEntity::getStatus, job.getStatus())
            .set(FlinkSqlJobEntity::getLastError, job.getLastError())
            .set(FlinkSqlJobEntity::getAlertState, job.getAlertState()));
        return ApiResponse.ok(job);
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('realtime:sql-job:stop')")
    public ApiResponse<ActionResult> stop(@PathVariable Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        if (job.getFlinkJobId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "还没有启动过，无需停止");
        }
        ChangeApprovalService.GateResult gate = changeApprovalService.gate(
            ChangeApprovalService.ActionType.FLINK_SQL_JOB_STOP, id, job.getEnvironment(), "SQL 流作业: " + job.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyStop(id);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyStop(Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        String savepointPath = flinkStreamSubmissionClient.stopWithSavepoint(job.getFlinkJobId());
        job.setStatus("CANCELED");
        job.setSavepointPath(savepointPath);
        flinkSqlJobMapper.updateById(job);
    }

    // See FlinkStreamJobController.clearSavepoint() - same reasoning, mirrored here.
    @PostMapping("/{id}/clear-savepoint")
    @PreAuthorize("hasAuthority('realtime:sql-job:clear-savepoint')")
    public ApiResponse<FlinkSqlJobEntity> clearSavepoint(@PathVariable Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        if (job.getSavepointPath() == null || job.getSavepointPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前没有保存点，无需清除");
        }
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, id)
            .set(FlinkSqlJobEntity::getSavepointPath, null));
        job.setSavepointPath(null);
        return ApiResponse.ok(job);
    }

    @GetMapping("/{id}/status")
    @PreAuthorize("hasAuthority('realtime:sql-job:view')")
    public ApiResponse<FlinkSqlJobEntity> refreshStatus(@PathVariable Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        if (job.getFlinkJobId() == null || !"RUNNING".equals(job.getStatus())) {
            return ApiResponse.ok(job);
        }
        FlinkStreamSubmissionClient.FlinkJobStatus status = flinkStreamSubmissionClient.status(job.getFlinkJobId());
        job.setStatus(status.state());
        job.setLastError(status.message());
        flinkSqlJobMapper.updateById(job);
        return ApiResponse.ok(job);
    }

    private FlinkSqlJobEntity requireJob(Long id) {
        FlinkSqlJobEntity job = flinkSqlJobMapper.selectById(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SQL 流作业不存在");
        }
        return job;
    }
}
