package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ActionResult;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.entity.JobVersionSnapshotEntity;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.security.EnvironmentGuard;
import com.company.dataops.console.service.RealtimeAlertService;
import com.company.dataops.console.service.approval.ChangeApprovalService;
import com.company.dataops.console.service.coordination.JobOperationCoordinator;
import com.company.dataops.console.service.flink.FlinkSqlGatewayClient;
import com.company.dataops.console.service.flink.FlinkSqlJobSubmissionService;
import com.company.dataops.console.service.flink.FlinkSqlReplayService;
import com.company.dataops.console.service.flink.FlinkSqlSecretResolver;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.versioning.JobVersionSnapshotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.beans.BeanUtils;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final FlinkSqlReplayService flinkSqlReplayService;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final RealtimeAlertService realtimeAlertService;
    private final EnvironmentGuard environmentGuard;
    private final ChangeApprovalService changeApprovalService;
    private final JobVersionSnapshotService jobVersionSnapshotService;
    private final JobOperationCoordinator jobOperationCoordinator;
    private final ObjectMapper objectMapper;
    private final FlinkSqlSecretResolver flinkSqlSecretResolver;
    private final String frontendUrl;

    public FlinkSqlJobController(
        FlinkSqlJobMapper flinkSqlJobMapper,
        FlinkSqlJobSubmissionService flinkSqlJobSubmissionService,
        FlinkSqlReplayService flinkSqlReplayService,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        RealtimeAlertService realtimeAlertService,
        EnvironmentGuard environmentGuard,
        ChangeApprovalService changeApprovalService,
        JobVersionSnapshotService jobVersionSnapshotService,
        JobOperationCoordinator jobOperationCoordinator,
        ObjectMapper objectMapper,
        FlinkSqlSecretResolver flinkSqlSecretResolver,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.flinkSqlJobMapper = flinkSqlJobMapper;
        this.flinkSqlJobSubmissionService = flinkSqlJobSubmissionService;
        this.flinkSqlReplayService = flinkSqlReplayService;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
        this.realtimeAlertService = realtimeAlertService;
        this.environmentGuard = environmentGuard;
        this.changeApprovalService = changeApprovalService;
        this.jobVersionSnapshotService = jobVersionSnapshotService;
        this.jobOperationCoordinator = jobOperationCoordinator;
        this.objectMapper = objectMapper;
        this.flinkSqlSecretResolver = flinkSqlSecretResolver;
        this.frontendUrl = frontendUrl;
        changeApprovalService.register(ChangeApprovalService.ActionType.FLINK_SQL_JOB_DELETE, this::applyDelete);
        changeApprovalService.register(ChangeApprovalService.ActionType.FLINK_SQL_JOB_STOP, this::applyStop);
        changeApprovalService.registerWithPayload(ChangeApprovalService.ActionType.FLINK_SQL_JOB_REPLAY, this::applyReplay);
        changeApprovalService.registerWithPayload(ChangeApprovalService.ActionType.FLINK_SQL_JOB_ROLLBACK, this::applyRollback);
    }

    private static final String ENTITY_TYPE = "FLINK_SQL_JOB";

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
        flinkSqlSecretResolver.requireReferencesOnly(job.getSqlScript());
        flinkSqlJobSubmissionService.assertJobShape(FlinkSqlGatewayClient.splitStatements(job.getSqlScript()));
        if (job.getEnvironment() == null || job.getEnvironment().isBlank()) {
            job.setEnvironment("DEV");
        }
        job.setStatus("DRAFT");
        job.setFlinkJobId(null);
        job.setSavepointPath(null);
        job.setLastError(null);
        flinkSqlJobMapper.insert(job);
        jobVersionSnapshotService.recordVersion(ENTITY_TYPE, job.getId(), buildConfigSnapshot(job), null, null, "创建", null);
        return ApiResponse.ok(job);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:sql-job:update')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody FlinkSqlJobEntity job) {
        flinkSqlSecretResolver.requireReferencesOnly(job.getSqlScript());
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
        jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(job), null, null, "编辑保存", null);
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
    public ApiResponse<FlinkSqlJobEntity> start(
        @PathVariable Long id,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return jobOperationCoordinator.execute(ENTITY_TYPE, id, "START", idempotencyKey, Duration.ofMinutes(5),
            () -> startUnlocked(id));
    }

    private ApiResponse<FlinkSqlJobEntity> startUnlocked(Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        if (Boolean.TRUE.equals(job.getSchemaBlocked())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "作业已被 Schema 兼容性保护阻断：" + job.getSchemaBlockReason());
        }
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
        jobOperationCoordinator.execute(ENTITY_TYPE, id, "STOP", null, Duration.ofMinutes(10),
            () -> applyStopUnlocked(id));
    }

    private void applyStopUnlocked(Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        String savepointPath = flinkStreamSubmissionClient.stopOrCancel(job.getFlinkJobId());
        job.setStatus("CANCELED");
        job.setSavepointPath(savepointPath);
        // Targeted update - see FlinkStreamJobController.applyStop()'s
        // identical comment: stopWithSavepoint() is a slow network call, so a
        // blanket updateById() here could stomp a concurrent start()'s fresh
        // flinkJobId or the poller's alert/backpressure/lag fields.
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, job.getId())
            .set(FlinkSqlJobEntity::getStatus, job.getStatus())
            .set(FlinkSqlJobEntity::getSavepointPath, job.getSavepointPath()));
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

    @PostMapping("/{id}/clear-schema-block")
    @PreAuthorize("hasAuthority('realtime:sql-job:update')")
    public ApiResponse<FlinkSqlJobEntity> clearSchemaBlock(@PathVariable Long id) {
        FlinkSqlJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, id)
            .set(FlinkSqlJobEntity::getSchemaBlocked, false)
            .set(FlinkSqlJobEntity::getSchemaBlockReason, null));
        job.setSchemaBlocked(false);
        job.setSchemaBlockReason(null);
        return ApiResponse.ok(job);
    }

    /**
     * Tier 3 item 1 of the reliability roadmap ("历史数据回放") - resubmits
     * the job reading its Kafka source(s) from a specific point in time (or
     * from the very beginning) instead of wherever its consumer group last
     * left off, via a one-time rewritten copy of the script (see
     * FlinkSqlReplayService) - the job's own stored sqlScript is never
     * touched. PROD-gated the same way as a rolling upgrade: resubmitting
     * against a live sink risks duplicate/reprocessed writes if that sink
     * isn't idempotent, which is exactly the kind of thing a second pair of
     * eyes should catch before it happens to a production table.
     */
    @PostMapping("/{id}/replay")
    @PreAuthorize("hasAuthority('realtime:sql-job:start')")
    public ApiResponse<ActionResult> replay(@PathVariable Long id, @RequestBody ReplayRequest request) {
        FlinkSqlJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        if (Boolean.TRUE.equals(job.getSchemaBlocked())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先确认 Schema 兼容性并解除阻断，再执行回放");
        }
        if (job.getFlinkJobId() != null && flinkStreamSubmissionClient.isRunning(job.getFlinkJobId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作业正在运行中，请先停止再重放");
        }
        // Validated here (not just in applyReplay()) so a malformed request
        // fails immediately instead of only surfacing once an approver
        // eventually processes it.
        FlinkSqlReplayService.ReplayMode mode = parseReplayMode(request.mode());
        try {
            flinkSqlReplayService.buildReplayScript(job.getSqlScript(), mode, request.timestampMillis());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化重放参数失败：" + exception.getMessage());
        }
        ChangeApprovalService.GateResult gate = changeApprovalService.gateWithPayload(
            ChangeApprovalService.ActionType.FLINK_SQL_JOB_REPLAY, id, job.getEnvironment(), payload, "SQL 流作业重放: " + job.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyReplay(id, payload);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyReplay(Long id, String payload) {
        jobOperationCoordinator.execute(ENTITY_TYPE, id, "REPLAY", null, Duration.ofMinutes(10),
            () -> applyReplayUnlocked(id, payload));
    }

    private void applyReplayUnlocked(Long id, String payload) {
        ReplayRequest request;
        try {
            request = objectMapper.readValue(payload, ReplayRequest.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析重放参数失败：" + exception.getMessage());
        }
        FlinkSqlJobEntity job = requireJob(id);
        String replayScript = flinkSqlReplayService.buildReplayScript(job.getSqlScript(), parseReplayMode(request.mode()), request.timestampMillis());
        FlinkSqlJobEntity replayJob = new FlinkSqlJobEntity();
        BeanUtils.copyProperties(job, replayJob);
        replayJob.setSqlScript(replayScript); // one-time override - job's own stored sqlScript is untouched

        try {
            String flinkJobId = flinkSqlJobSubmissionService.submit(replayJob);
            job.setFlinkJobId(flinkJobId);
            job.setStatus("RUNNING");
            job.setLastError(null);
        } catch (ResponseStatusException exception) {
            job.setStatus("FAILED");
            job.setLastError(exception.getReason());
        }
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, job.getId())
            .set(FlinkSqlJobEntity::getFlinkJobId, job.getFlinkJobId())
            .set(FlinkSqlJobEntity::getStatus, job.getStatus())
            .set(FlinkSqlJobEntity::getLastError, job.getLastError()));
    }

    private FlinkSqlReplayService.ReplayMode parseReplayMode(String mode) {
        try {
            return FlinkSqlReplayService.ReplayMode.valueOf(mode);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的重放模式：" + mode);
        }
    }

    public record ReplayRequest(String mode, Long timestampMillis) {
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
        // Targeted update - see FlinkStreamJobController.refreshStatus()'s
        // identical comment: status() is a network round-trip, so a full
        // updateById() here could clobber a concurrent start()'s or the
        // poller's writes.
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, job.getId())
            .set(FlinkSqlJobEntity::getStatus, job.getStatus())
            .set(FlinkSqlJobEntity::getLastError, job.getLastError()));
        return ApiResponse.ok(job);
    }

    /** See FlinkStreamJobController.rollback()/applyRollback() - identical semantics, mirrored here for SQL jobs. */
    @PostMapping("/{id}/rollback/{versionNo}")
    @PreAuthorize("hasAuthority('realtime:sql-job:update')")
    public ApiResponse<ActionResult> rollback(@PathVariable Long id, @PathVariable Integer versionNo) {
        FlinkSqlJobEntity existing = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(existing.getEnvironment());
        if (Boolean.TRUE.equals(existing.getSchemaBlocked())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先确认 Schema 兼容性并解除阻断，再执行回滚");
        }
        jobVersionSnapshotService.requireVersion(ENTITY_TYPE, id, versionNo); // 404s early if the version doesn't exist
        String payload = String.valueOf(versionNo);
        ChangeApprovalService.GateResult gate = changeApprovalService.gateWithPayload(
            ChangeApprovalService.ActionType.FLINK_SQL_JOB_ROLLBACK, id, existing.getEnvironment(), payload, "SQL 流作业回滚至版本 " + versionNo + ": " + existing.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyRollback(id, payload);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyRollback(Long id, String payload) {
        jobOperationCoordinator.execute(ENTITY_TYPE, id, "ROLLBACK", null, Duration.ofMinutes(10),
            () -> applyRollbackUnlocked(id, payload));
    }

    private void applyRollbackUnlocked(Long id, String payload) {
        int versionNo = Integer.parseInt(payload);
        FlinkSqlJobEntity existing = requireJob(id);
        JobVersionSnapshotEntity target = jobVersionSnapshotService.requireVersion(ENTITY_TYPE, id, versionNo);
        FlinkSqlJobEntity targetConfig = jobVersionSnapshotService.readConfig(target, FlinkSqlJobEntity.class);
        targetConfig.setId(id);
        targetConfig.setStatus(null);
        targetConfig.setFlinkJobId(null);
        targetConfig.setSavepointPath(null);
        targetConfig.setLastError(null);
        setDeploymentState(id, "PREPARING", "ROLLBACK", "正在准备回滚至版本 " + versionNo);
        if ("RUNNING".equals(existing.getStatus()) && existing.getFlinkJobId() != null) {
            try {
                setDeploymentState(id, "STOPPING", "ROLLBACK", "正在停止当前实例");
                // Deliberately discarded - see FlinkStreamJobController's identical comment.
                flinkStreamSubmissionClient.stopWithSavepoint(existing.getFlinkJobId());
            } catch (Exception exception) {
                setDeploymentState(id, "ROLLBACK", "ROLLBACK", "无法停止当前实例，已保留当前配置和运行状态：" + exception.getMessage());
                return;
            }
        }

        flinkSqlJobMapper.updateById(targetConfig);
        setPendingDeployment(id, "ROLLBACK", target.getSavepointPath(), "正在部署版本 " + versionNo);
        FlinkSqlJobEntity toSubmit = requireJob(id);
        toSubmit.setSavepointPath(target.getSavepointPath());
        try {
            String flinkJobId = flinkSqlJobSubmissionService.submit(toSubmit);
            flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                .eq(FlinkSqlJobEntity::getId, id)
                .set(FlinkSqlJobEntity::getFlinkJobId, flinkJobId)
                .set(FlinkSqlJobEntity::getStatus, "STARTING")
                .set(FlinkSqlJobEntity::getDeploymentStatus, "VERIFYING")
                .set(FlinkSqlJobEntity::getDeploymentMessage, "回滚实例已提交，等待 Flink 确认运行")
                .set(FlinkSqlJobEntity::getDeploymentUpdatedAt, java.time.LocalDateTime.now())
                .set(FlinkSqlJobEntity::getSavepointPath, target.getSavepointPath())
                .set(FlinkSqlJobEntity::getLastError, null));
            jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(toSubmit), target.getSavepointPath(), flinkJobId, "回滚至版本 " + versionNo, versionNo);
        } catch (Exception exception) {
            flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                .eq(FlinkSqlJobEntity::getId, id)
                .set(FlinkSqlJobEntity::getStatus, "FAILED")
                .set(FlinkSqlJobEntity::getDeploymentStatus, "ROLLBACK")
                .set(FlinkSqlJobEntity::getDeploymentMessage, "目标版本启动失败")
                .set(FlinkSqlJobEntity::getDeploymentUpdatedAt, java.time.LocalDateTime.now())
                .set(FlinkSqlJobEntity::getSavepointPath, target.getSavepointPath())
                .set(FlinkSqlJobEntity::getLastError, "回滚失败：版本 " + versionNo + " 的配置启动失败（" + exception.getMessage() + "）"));
            jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(toSubmit), target.getSavepointPath(), null, "回滚至版本 " + versionNo + "（部署失败）", versionNo);
        }
    }

    private void setDeploymentState(Long id, String state, String operation, String message) {
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, id)
            .set(FlinkSqlJobEntity::getDeploymentStatus, state)
            .set(FlinkSqlJobEntity::getDeploymentOperation, operation)
            .set(FlinkSqlJobEntity::getDeploymentMessage, message)
            .set(FlinkSqlJobEntity::getDeploymentUpdatedAt, java.time.LocalDateTime.now()));
    }

    private void setPendingDeployment(Long id, String operation, String resumePath, String message) {
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, id)
            .set(FlinkSqlJobEntity::getDeploymentStatus, "DEPLOYING")
            .set(FlinkSqlJobEntity::getDeploymentOperation, operation)
            .set(FlinkSqlJobEntity::getPendingResumePath, resumePath)
            .set(FlinkSqlJobEntity::getDeploymentMessage, message)
            .set(FlinkSqlJobEntity::getDeploymentUpdatedAt, java.time.LocalDateTime.now()));
    }

    /** Config-only copy for diffing/rollback - nulls out everything that's a runtime fact rather than part of the definition. */
    private FlinkSqlJobEntity buildConfigSnapshot(FlinkSqlJobEntity job) {
        FlinkSqlJobEntity copy = new FlinkSqlJobEntity();
        BeanUtils.copyProperties(job, copy);
        copy.setId(null);
        copy.setFlinkJobId(null);
        copy.setSavepointPath(null);
        copy.setStatus(null);
        copy.setLastError(null);
        copy.setAlertState(null);
        copy.setBackpressureRatio(null);
        copy.setBackpressureAlertState(null);
        copy.setConsumerLagRecords(null);
        copy.setConsumerLagAlertState(null);
        copy.setCreatedAt(null);
        copy.setUpdatedAt(null);
        return copy;
    }

    private FlinkSqlJobEntity requireJob(Long id) {
        FlinkSqlJobEntity job = flinkSqlJobMapper.selectById(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SQL 流作业不存在");
        }
        return job;
    }
}
