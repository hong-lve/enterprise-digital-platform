package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ActionResult;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.security.EnvironmentGuard;
import com.company.dataops.console.service.RealtimeAlertService;
import com.company.dataops.console.service.approval.ChangeApprovalService;
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

@RestController
@RequestMapping("/realtime/flink-jobs")
public class FlinkStreamJobController {
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final RealtimeAlertService realtimeAlertService;
    private final EnvironmentGuard environmentGuard;
    private final ChangeApprovalService changeApprovalService;
    private final String frontendUrl;

    public FlinkStreamJobController(
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        RealtimeAlertService realtimeAlertService,
        EnvironmentGuard environmentGuard,
        ChangeApprovalService changeApprovalService,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
        this.realtimeAlertService = realtimeAlertService;
        this.environmentGuard = environmentGuard;
        this.changeApprovalService = changeApprovalService;
        this.frontendUrl = frontendUrl;
        changeApprovalService.register(ChangeApprovalService.ActionType.FLINK_STREAM_JOB_DELETE, this::applyDelete);
        changeApprovalService.register(ChangeApprovalService.ActionType.FLINK_STREAM_JOB_STOP, this::applyStop);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:flink:view')")
    public ApiResponse<PageResult<FlinkStreamJobEntity>> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long pageSize) {
        Page<FlinkStreamJobEntity> page = flinkStreamJobMapper.selectPage(Page.of(current, pageSize), new LambdaQueryWrapper<FlinkStreamJobEntity>().orderByDesc(FlinkStreamJobEntity::getId));
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:flink:view')")
    public ApiResponse<FlinkStreamJobEntity> detail(@PathVariable Long id) {
        return ApiResponse.ok(requireJob(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('realtime:flink:create')")
    public ApiResponse<FlinkStreamJobEntity> create(@Valid @RequestBody FlinkStreamJobEntity job) {
        // Not gated - a freshly created row is DRAFT and hasn't touched any
        // live infra yet, only start()/stop()/delete()/update() do.
        if (job.getEnvironment() == null || job.getEnvironment().isBlank()) {
            job.setEnvironment("DEV");
        }
        job.setStatus("DRAFT");
        job.setFlinkJobId(null);
        job.setSavepointPath(null);
        job.setLastError(null);
        flinkStreamJobMapper.insert(job);
        return ApiResponse.ok(job);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:flink:update')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody FlinkStreamJobEntity job) {
        FlinkStreamJobEntity existing = requireJob(id);
        // Check both the row's current environment and the incoming payload's
        // - otherwise someone without the PROD permission could edit a DEV
        // row and flip it to PROD in the same request.
        environmentGuard.requirePermissionForEnvironment(existing.getEnvironment());
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        job.setId(id);
        job.setStatus(null);
        job.setFlinkJobId(null);
        job.setSavepointPath(null);
        job.setLastError(null);
        flinkStreamJobMapper.updateById(job);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:flink:delete')")
    public ApiResponse<ActionResult> delete(@PathVariable Long id) {
        FlinkStreamJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        ChangeApprovalService.GateResult gate = changeApprovalService.gate(
            ChangeApprovalService.ActionType.FLINK_STREAM_JOB_DELETE, id, job.getEnvironment(), "Flink 流作业: " + job.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyDelete(id);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyDelete(Long id) {
        FlinkStreamJobEntity job = requireJob(id);
        if ("RUNNING".equals(job.getStatus()) && job.getFlinkJobId() != null) {
            try {
                flinkStreamSubmissionClient.stopWithSavepoint(job.getFlinkJobId());
            } catch (Exception ignored) {
                // best-effort: Flink cluster being unreachable shouldn't block removing the local row
            }
        }
        flinkStreamJobMapper.deleteById(id);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('realtime:flink:start')")
    public ApiResponse<FlinkStreamJobEntity> start(@PathVariable Long id) {
        FlinkStreamJobEntity job = requireJob(id);
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
                flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                    .eq(FlinkStreamJobEntity::getId, job.getId())
                    .set(FlinkStreamJobEntity::getStatus, "RUNNING")
                    .set(FlinkStreamJobEntity::getLastError, null));
            }
            return ApiResponse.ok(job);
        }
        try {
            String flinkJobId = flinkStreamSubmissionClient.submit(job);
            job.setFlinkJobId(flinkJobId);
            job.setStatus("RUNNING");
            job.setLastError(null);
            if ("ALERTING".equals(job.getAlertState())) {
                realtimeAlertService.notifyRecovery(
                    new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "JOB_FAILURE"),
                    job.getOwner(),
                    "Flink 流作业已恢复：" + job.getName(),
                    frontendUrl + "/realtime/flink-jobs"
                );
                job.setAlertState("OK");
            }
        } catch (ResponseStatusException exception) {
            job.setStatus("FAILED");
            job.setLastError(exception.getReason());
        }
        // updateById() silently skips null fields (MyBatis-Plus's default
        // NOT_NULL update strategy) - fine for update()'s "don't touch
        // fields the edit form didn't send" use just below, but wrong here:
        // clearing lastError to null on a successful (re)start needs to
        // actually reach the database, or the old error text lingers next
        // to a healthy RUNNING status forever. A targeted UpdateWrapper
        // listing only the fields this endpoint owns also means it can't
        // stomp on backpressure/consumer-lag fields the pollers manage.
        flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, job.getId())
            .set(FlinkStreamJobEntity::getFlinkJobId, job.getFlinkJobId())
            .set(FlinkStreamJobEntity::getStatus, job.getStatus())
            .set(FlinkStreamJobEntity::getLastError, job.getLastError())
            .set(FlinkStreamJobEntity::getAlertState, job.getAlertState()));
        return ApiResponse.ok(job);
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('realtime:flink:stop')")
    public ApiResponse<ActionResult> stop(@PathVariable Long id) {
        FlinkStreamJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        if (job.getFlinkJobId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "还没有启动过，无需停止");
        }
        ChangeApprovalService.GateResult gate = changeApprovalService.gate(
            ChangeApprovalService.ActionType.FLINK_STREAM_JOB_STOP, id, job.getEnvironment(), "Flink 流作业: " + job.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyStop(id);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyStop(Long id) {
        FlinkStreamJobEntity job = requireJob(id);
        String savepointPath = flinkStreamSubmissionClient.stopWithSavepoint(job.getFlinkJobId());
        job.setStatus("CANCELED");
        job.setSavepointPath(savepointPath);
        // Targeted update, not updateById(job) - stopWithSavepoint() is a slow
        // network call, so job is a stale snapshot from requireJob() by the
        // time we write. Same lost-update risk as start()/the poller: a
        // blanket write here could stomp a concurrent start()'s fresh
        // flinkJobId or the poller's alert/backpressure/lag fields.
        flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, job.getId())
            .set(FlinkStreamJobEntity::getStatus, job.getStatus())
            .set(FlinkStreamJobEntity::getSavepointPath, job.getSavepointPath()));
    }

    // Separate from update()'s existing savepointPath=null clear (which only
    // fires when the job's own definition changes) - this lets a user force a
    // fresh start without touching the job definition, e.g. after a schema
    // change upstream that would make resuming from an old savepoint invalid
    // in ways the platform can't detect on its own.
    @PostMapping("/{id}/clear-savepoint")
    @PreAuthorize("hasAuthority('realtime:flink:clear-savepoint')")
    public ApiResponse<FlinkStreamJobEntity> clearSavepoint(@PathVariable Long id) {
        FlinkStreamJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        if (job.getSavepointPath() == null || job.getSavepointPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前没有保存点，无需清除");
        }
        flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, id)
            .set(FlinkStreamJobEntity::getSavepointPath, null));
        job.setSavepointPath(null);
        return ApiResponse.ok(job);
    }

    @GetMapping("/{id}/status")
    @PreAuthorize("hasAuthority('realtime:flink:view')")
    public ApiResponse<FlinkStreamJobEntity> refreshStatus(@PathVariable Long id) {
        FlinkStreamJobEntity job = requireJob(id);
        // Only re-poll Flink for jobs we still think are RUNNING. A graceful
        // stop-with-savepoint leaves the Flink-side job in FINISHED state (Flink
        // doesn't distinguish "stopped on purpose" from "source ran out of data"
        // at the REST API level) - re-querying a job we already recorded as
        // CANCELED would flip it back to a misleading "FINISHED" label.
        if (job.getFlinkJobId() == null || !"RUNNING".equals(job.getStatus())) {
            return ApiResponse.ok(job);
        }
        FlinkStreamSubmissionClient.FlinkJobStatus status = flinkStreamSubmissionClient.status(job.getFlinkJobId());
        job.setStatus(status.state());
        job.setLastError(status.message());
        // Targeted update - same stale-snapshot race as applyStop(): the
        // status() call above is a network round-trip, so a full updateById()
        // here could clobber a concurrent start()'s or the poller's writes.
        flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, job.getId())
            .set(FlinkStreamJobEntity::getStatus, job.getStatus())
            .set(FlinkStreamJobEntity::getLastError, job.getLastError()));
        return ApiResponse.ok(job);
    }

    private FlinkStreamJobEntity requireJob(Long id) {
        FlinkStreamJobEntity job = flinkStreamJobMapper.selectById(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flink 流作业不存在");
        }
        return job;
    }
}
