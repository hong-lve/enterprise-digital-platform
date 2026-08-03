package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ActionResult;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.FlinkCheckpointHistoryEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.entity.JobVersionSnapshotEntity;
import com.company.dataops.console.mapper.FlinkCheckpointHistoryMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.security.EnvironmentGuard;
import com.company.dataops.console.service.RealtimeAlertService;
import com.company.dataops.console.service.approval.ChangeApprovalService;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.recovery.RecoveryOrchestrator;
import com.company.dataops.console.service.versioning.JobVersionSnapshotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/realtime/flink-jobs")
public class FlinkStreamJobController {
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkCheckpointHistoryMapper flinkCheckpointHistoryMapper;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final RealtimeAlertService realtimeAlertService;
    private final EnvironmentGuard environmentGuard;
    private final RecoveryOrchestrator recoveryOrchestrator;
    private final ChangeApprovalService changeApprovalService;
    private final JobVersionSnapshotService jobVersionSnapshotService;
    private final ObjectMapper objectMapper;
    private final String frontendUrl;

    public FlinkStreamJobController(
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkCheckpointHistoryMapper flinkCheckpointHistoryMapper,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        RealtimeAlertService realtimeAlertService,
        EnvironmentGuard environmentGuard,
        RecoveryOrchestrator recoveryOrchestrator,
        ChangeApprovalService changeApprovalService,
        JobVersionSnapshotService jobVersionSnapshotService,
        ObjectMapper objectMapper,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkCheckpointHistoryMapper = flinkCheckpointHistoryMapper;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
        this.realtimeAlertService = realtimeAlertService;
        this.environmentGuard = environmentGuard;
        this.recoveryOrchestrator = recoveryOrchestrator;
        this.changeApprovalService = changeApprovalService;
        this.jobVersionSnapshotService = jobVersionSnapshotService;
        this.objectMapper = objectMapper;
        this.frontendUrl = frontendUrl;
        changeApprovalService.register(ChangeApprovalService.ActionType.FLINK_STREAM_JOB_DELETE, this::applyDelete);
        changeApprovalService.register(ChangeApprovalService.ActionType.FLINK_STREAM_JOB_STOP, this::applyStop);
        changeApprovalService.registerWithPayload(ChangeApprovalService.ActionType.FLINK_STREAM_JOB_UPGRADE, this::applyUpgrade);
        changeApprovalService.registerWithPayload(ChangeApprovalService.ActionType.FLINK_STREAM_JOB_ROLLBACK, this::applyRollback);
    }

    private static final String ENTITY_TYPE = "FLINK_STREAM_JOB";

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
        jobVersionSnapshotService.recordVersion(ENTITY_TYPE, job.getId(), buildConfigSnapshot(job), null, null, "创建", null);
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
        jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(job), null, null, "编辑保存", null);
        return ApiResponse.ok();
    }

    /**
     * Scaled-down equivalent of the reliability roadmap's "Savepoint Upgrade
     * Mode" (normally a Flink Kubernetes Operator feature - see
     * [[project_reliability_hardening_roadmap]]'s note on why this project
     * stayed on the shared standalone cluster instead of a second local K8s
     * cluster). Unlike update() above, which just rewrites the DB row and
     * leaves whatever's actually running on the cluster untouched until the
     * next manual stop/start, this applies a new definition to an
     * *already-running* job as one guarded action: stop-with-savepoint the
     * old instance, save the new definition, resume from that exact
     * savepoint under the new config. Only meaningful for a RUNNING job -
     * anything else should just use the plain update() endpoint.
     */
    @PostMapping("/{id}/upgrade")
    @PreAuthorize("hasAuthority('realtime:flink:update')")
    public ApiResponse<ActionResult> upgrade(@PathVariable Long id, @Valid @RequestBody FlinkStreamJobEntity newDefinition) {
        FlinkStreamJobEntity existing = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(existing.getEnvironment());
        environmentGuard.requirePermissionForEnvironment(newDefinition.getEnvironment());
        if (!"RUNNING".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有运行中的作业才需要滚动升级，未运行的作业请直接编辑保存");
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(newDefinition);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化升级内容失败：" + exception.getMessage());
        }
        ChangeApprovalService.GateResult gate = changeApprovalService.gateWithPayload(
            ChangeApprovalService.ActionType.FLINK_STREAM_JOB_UPGRADE, id, existing.getEnvironment(), payload, "Flink 流作业滚动升级: " + existing.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyUpgrade(id, payload);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyUpgrade(Long id, String payload) {
        FlinkStreamJobEntity newDefinition;
        try {
            newDefinition = objectMapper.readValue(payload, FlinkStreamJobEntity.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析升级内容失败：" + exception.getMessage());
        }
        FlinkStreamJobEntity existing = requireJob(id);
        // Save the new definition first (everything except the runtime
        // fields below) so it's recorded even if the stop/resume dance
        // fails partway - a failed upgrade shouldn't lose the edit itself.
        newDefinition.setId(id);
        newDefinition.setStatus(null);
        newDefinition.setFlinkJobId(null);
        newDefinition.setSavepointPath(null);
        newDefinition.setLastError(null);
        flinkStreamJobMapper.updateById(newDefinition);

        String savepointPath;
        try {
            savepointPath = flinkStreamSubmissionClient.stopWithSavepoint(existing.getFlinkJobId());
        } catch (Exception exception) {
            flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getId, id)
                .set(FlinkStreamJobEntity::getStatus, "FAILED")
                .set(FlinkStreamJobEntity::getLastError, "滚动升级失败：无法触发保存点停止（" + exception.getMessage() + "），旧实例可能仍在运行，请人工检查"));
            jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(newDefinition), null, null, "滚动升级失败：无法停止旧实例", null);
            return;
        }

        FlinkStreamJobEntity toSubmit = requireJob(id); // reload - now has the freshly-saved new definition
        toSubmit.setSavepointPath(savepointPath);
        try {
            String flinkJobId = flinkStreamSubmissionClient.submit(toSubmit);
            flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getId, id)
                .set(FlinkStreamJobEntity::getFlinkJobId, flinkJobId)
                .set(FlinkStreamJobEntity::getStatus, "RUNNING")
                .set(FlinkStreamJobEntity::getSavepointPath, savepointPath)
                .set(FlinkStreamJobEntity::getLastError, null));
            jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(toSubmit), savepointPath, flinkJobId, "滚动升级", null);
        } catch (Exception exception) {
            // The old instance is already stopped - can't un-stop it - but the
            // savepoint it left behind is safe, so a human can manually 启动
            // once the new definition's actual problem (bad jar path, wrong
            // entry class, insufficient capacity) is fixed.
            jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(toSubmit), savepointPath, null, "滚动升级失败：新配置启动失败，保存点已保留", null);
            flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getId, id)
                .set(FlinkStreamJobEntity::getStatus, "FAILED")
                .set(FlinkStreamJobEntity::getSavepointPath, savepointPath)
                .set(FlinkStreamJobEntity::getLastError, "滚动升级失败：新配置启动失败（" + exception.getMessage() + "），保存点已保留在 " + savepointPath + "，可以人工使用该保存点手动启动"));
        }
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
            recoveryOrchestrator.recordRecovered("FLINK_JOB", job.getId(), job.getName());
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
            recordRestoreOutcome(job, "VERIFIED");
            // A manual (re)start doesn't go through FlinkStreamJobPollingScheduler's
            // own FAILED->RUNNING detection, so its recovery state wouldn't
            // otherwise be cleared - a later, unrelated failure would be
            // wrongly treated as still mid-incident (wrong tier/circuit state).
            recoveryOrchestrator.recordRecovered("FLINK_JOB", job.getId(), job.getName());
        } catch (ResponseStatusException exception) {
            job.setStatus("FAILED");
            job.setLastError(exception.getReason());
            recordRestoreOutcome(job, "FAILED");
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
        // Intentional non-failure state - don't leave a stale recovery-attempt
        // count hanging around for it, but don't log it as "RECOVERED" either
        // since nothing was actually recovered.
        recoveryOrchestrator.reset("FLINK_JOB", job.getId(), job.getName(), "手动停止，清除恢复状态");
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

    // Item 1 of the reliability roadmap's "recovery verification": rather
    // than synthetically testing a savepoint against a throwaway job (risky
    // against real production Kafka topics/sinks for no real benefit), this
    // records what actually happened the one time it matters - a real
    // resume attempt via this exact endpoint. Best-effort: if
    // FlinkCheckpointHistoryScheduler hasn't synced this savepoint into
    // flink_checkpoint_history yet (or it's blank - a fresh, not resumed,
    // start), there's nothing to annotate.
    private void recordRestoreOutcome(FlinkStreamJobEntity job, String outcome) {
        if (job.getSavepointPath() == null || job.getSavepointPath().isBlank()) {
            return;
        }
        FlinkCheckpointHistoryEntity entry = flinkCheckpointHistoryMapper.selectOne(new LambdaQueryWrapper<FlinkCheckpointHistoryEntity>()
            .eq(FlinkCheckpointHistoryEntity::getJobId, job.getId())
            .eq(FlinkCheckpointHistoryEntity::getExternalPath, job.getSavepointPath()));
        if (entry == null) {
            return;
        }
        entry.setRestoreOutcome(outcome);
        entry.setRestoreCheckedAt(LocalDateTime.now());
        flinkCheckpointHistoryMapper.updateById(entry);
    }

    @GetMapping("/{id}/checkpoints")
    @PreAuthorize("hasAuthority('realtime:flink:view')")
    public ApiResponse<List<FlinkCheckpointHistoryEntity>> checkpoints(@PathVariable Long id) {
        requireJob(id);
        return ApiResponse.ok(flinkCheckpointHistoryMapper.selectList(new LambdaQueryWrapper<FlinkCheckpointHistoryEntity>()
            .eq(FlinkCheckpointHistoryEntity::getJobId, id)
            .orderByDesc(FlinkCheckpointHistoryEntity::getTriggerTimestamp)));
    }

    @PostMapping("/{id}/checkpoints/{checkpointId}/dispose")
    @PreAuthorize("hasAuthority('realtime:flink:checkpoint-manage')")
    public ApiResponse<Void> disposeCheckpoint(@PathVariable Long id, @PathVariable Long checkpointId) {
        FlinkStreamJobEntity job = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(job.getEnvironment());
        FlinkCheckpointHistoryEntity entry = flinkCheckpointHistoryMapper.selectOne(new LambdaQueryWrapper<FlinkCheckpointHistoryEntity>()
            .eq(FlinkCheckpointHistoryEntity::getJobId, id)
            .eq(FlinkCheckpointHistoryEntity::getCheckpointId, checkpointId));
        if (entry == null || entry.getExternalPath() == null || entry.getExternalPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该记录没有可删除的保存点文件");
        }
        if (entry.getExternalPath().equals(job.getSavepointPath())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "这是作业当前恢复点，不能删除");
        }
        flinkStreamSubmissionClient.disposeSavepoint(entry.getExternalPath());
        entry.setDisposed(true);
        flinkCheckpointHistoryMapper.updateById(entry);
        return ApiResponse.ok();
    }

    /**
     * "快速回滚": redeploys an earlier recorded version's exact config,
     * resuming from *that version's own* recorded savepoint (not wherever
     * the about-to-be-abandoned current run left off) - simplest, safest
     * rollback semantic since it doesn't assume the abandoned run's state is
     * still compatible with the older config. See JobVersionSnapshotService.
     */
    @PostMapping("/{id}/rollback/{versionNo}")
    @PreAuthorize("hasAuthority('realtime:flink:update')")
    public ApiResponse<ActionResult> rollback(@PathVariable Long id, @PathVariable Integer versionNo) {
        FlinkStreamJobEntity existing = requireJob(id);
        environmentGuard.requirePermissionForEnvironment(existing.getEnvironment());
        jobVersionSnapshotService.requireVersion(ENTITY_TYPE, id, versionNo); // 404s early if the version doesn't exist
        String payload = String.valueOf(versionNo);
        ChangeApprovalService.GateResult gate = changeApprovalService.gateWithPayload(
            ChangeApprovalService.ActionType.FLINK_STREAM_JOB_ROLLBACK, id, existing.getEnvironment(), payload, "Flink 流作业回滚至版本 " + versionNo + ": " + existing.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyRollback(id, payload);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyRollback(Long id, String payload) {
        int versionNo = Integer.parseInt(payload);
        FlinkStreamJobEntity existing = requireJob(id);
        JobVersionSnapshotEntity target = jobVersionSnapshotService.requireVersion(ENTITY_TYPE, id, versionNo);
        FlinkStreamJobEntity targetConfig = jobVersionSnapshotService.readConfig(target, FlinkStreamJobEntity.class);
        targetConfig.setId(id);
        targetConfig.setStatus(null);
        targetConfig.setFlinkJobId(null);
        targetConfig.setSavepointPath(null);
        targetConfig.setLastError(null);
        flinkStreamJobMapper.updateById(targetConfig);

        if ("RUNNING".equals(existing.getStatus()) && existing.getFlinkJobId() != null) {
            try {
                // Deliberately discarded - rolling back means going back to
                // version N's own resume point, not preserving whatever
                // state the about-to-be-abandoned run had.
                flinkStreamSubmissionClient.stopWithSavepoint(existing.getFlinkJobId());
            } catch (Exception exception) {
                flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                    .eq(FlinkStreamJobEntity::getId, id)
                    .set(FlinkStreamJobEntity::getStatus, "FAILED")
                    .set(FlinkStreamJobEntity::getLastError, "回滚失败：无法停止当前运行实例（" + exception.getMessage() + "），请人工检查"));
                return;
            }
        }

        FlinkStreamJobEntity toSubmit = requireJob(id); // reload - now has version N's config
        toSubmit.setSavepointPath(target.getSavepointPath());
        try {
            String flinkJobId = flinkStreamSubmissionClient.submit(toSubmit);
            flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getId, id)
                .set(FlinkStreamJobEntity::getFlinkJobId, flinkJobId)
                .set(FlinkStreamJobEntity::getStatus, "RUNNING")
                .set(FlinkStreamJobEntity::getSavepointPath, target.getSavepointPath())
                .set(FlinkStreamJobEntity::getLastError, null));
            jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(toSubmit), target.getSavepointPath(), flinkJobId, "回滚至版本 " + versionNo, versionNo);
        } catch (Exception exception) {
            flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getId, id)
                .set(FlinkStreamJobEntity::getStatus, "FAILED")
                .set(FlinkStreamJobEntity::getSavepointPath, target.getSavepointPath())
                .set(FlinkStreamJobEntity::getLastError, "回滚失败：版本 " + versionNo + " 的配置启动失败（" + exception.getMessage() + "）"));
            jobVersionSnapshotService.recordVersion(ENTITY_TYPE, id, buildConfigSnapshot(toSubmit), target.getSavepointPath(), null, "回滚至版本 " + versionNo + "（部署失败）", versionNo);
        }
    }

    /** Config-only copy for diffing/rollback - nulls out everything that's a runtime fact rather than part of the definition. */
    private FlinkStreamJobEntity buildConfigSnapshot(FlinkStreamJobEntity job) {
        FlinkStreamJobEntity copy = new FlinkStreamJobEntity();
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
        copy.setCheckpointFailureAlertState(null);
        copy.setCreatedAt(null);
        copy.setUpdatedAt(null);
        return copy;
    }

    private FlinkStreamJobEntity requireJob(Long id) {
        FlinkStreamJobEntity job = flinkStreamJobMapper.selectById(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flink 流作业不存在");
        }
        return job;
    }
}
