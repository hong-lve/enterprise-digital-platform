package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ActionResult;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.CdcSourceMapper;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.security.EnvironmentGuard;
import com.company.dataops.console.service.RealtimeAlertService;
import com.company.dataops.console.service.approval.ChangeApprovalService;
import com.company.dataops.console.service.flink.SinkTableDdlBuilder;
import com.company.dataops.console.service.kafka.CdcTableSchemaService;
import com.company.dataops.console.service.kafka.DebeziumConnectorConfigBuilder;
import com.company.dataops.console.service.kafka.KafkaConnectClient;
import com.company.dataops.console.service.recovery.RecoveryOrchestrator;
import java.util.List;
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
@RequestMapping("/realtime/cdc-sources")
public class CdcSourceController {
    private final CdcSourceMapper cdcSourceMapper;
    private final DataSourceMapper dataSourceMapper;
    private final KafkaConnectClient kafkaConnectClient;
    private final DebeziumConnectorConfigBuilder configBuilder;
    private final RealtimeAlertService realtimeAlertService;
    private final EnvironmentGuard environmentGuard;
    private final RecoveryOrchestrator recoveryOrchestrator;
    private final CdcTableSchemaService cdcTableSchemaService;
    private final SinkTableDdlBuilder sinkTableDdlBuilder;
    private final ChangeApprovalService changeApprovalService;
    private final String frontendUrl;

    public CdcSourceController(
        CdcSourceMapper cdcSourceMapper,
        DataSourceMapper dataSourceMapper,
        KafkaConnectClient kafkaConnectClient,
        DebeziumConnectorConfigBuilder configBuilder,
        RealtimeAlertService realtimeAlertService,
        EnvironmentGuard environmentGuard,
        RecoveryOrchestrator recoveryOrchestrator,
        CdcTableSchemaService cdcTableSchemaService,
        SinkTableDdlBuilder sinkTableDdlBuilder,
        ChangeApprovalService changeApprovalService,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.cdcSourceMapper = cdcSourceMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.kafkaConnectClient = kafkaConnectClient;
        this.configBuilder = configBuilder;
        this.realtimeAlertService = realtimeAlertService;
        this.environmentGuard = environmentGuard;
        this.recoveryOrchestrator = recoveryOrchestrator;
        this.cdcTableSchemaService = cdcTableSchemaService;
        this.sinkTableDdlBuilder = sinkTableDdlBuilder;
        this.changeApprovalService = changeApprovalService;
        this.frontendUrl = frontendUrl;
        changeApprovalService.register(ChangeApprovalService.ActionType.CDC_SOURCE_DELETE, this::applyDelete);
        changeApprovalService.register(ChangeApprovalService.ActionType.CDC_SOURCE_STOP, this::applyStop);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:cdc:view')")
    public ApiResponse<PageResult<CdcSourceEntity>> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long pageSize) {
        Page<CdcSourceEntity> page = cdcSourceMapper.selectPage(Page.of(current, pageSize), new LambdaQueryWrapper<CdcSourceEntity>().orderByDesc(CdcSourceEntity::getId));
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:cdc:view')")
    public ApiResponse<CdcSourceEntity> detail(@PathVariable Long id) {
        return ApiResponse.ok(requireSource(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('realtime:cdc:create')")
    public ApiResponse<CdcSourceEntity> create(@Valid @RequestBody CdcSourceEntity source) {
        requireCdcDataSource(source.getDataSourceId());
        // Not gated - a freshly created row is DRAFT and hasn't touched any
        // live infra yet, only start()/stop()/delete()/update() do.
        if (source.getEnvironment() == null || source.getEnvironment().isBlank()) {
            source.setEnvironment("DEV");
        }
        source.setStatus("DRAFT");
        source.setConnectorName(null);
        source.setLastError(null);
        cdcSourceMapper.insert(source);
        return ApiResponse.ok(source);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:cdc:update')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody CdcSourceEntity source) {
        CdcSourceEntity existing = requireSource(id);
        // Check both the row's current environment and the incoming payload's
        // - otherwise someone without the PROD permission could edit a DEV
        // row and flip it to PROD in the same request.
        environmentGuard.requirePermissionForEnvironment(existing.getEnvironment());
        environmentGuard.requirePermissionForEnvironment(source.getEnvironment());
        requireCdcDataSource(source.getDataSourceId());
        source.setId(id);
        source.setStatus(null);
        source.setConnectorName(null);
        source.setLastError(null);
        cdcSourceMapper.updateById(source);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:cdc:delete')")
    public ApiResponse<ActionResult> delete(@PathVariable Long id) {
        CdcSourceEntity source = requireSource(id);
        environmentGuard.requirePermissionForEnvironment(source.getEnvironment());
        ChangeApprovalService.GateResult gate = changeApprovalService.gate(
            ChangeApprovalService.ActionType.CDC_SOURCE_DELETE, id, source.getEnvironment(), "CDC 数据源: " + source.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyDelete(id);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyDelete(Long id) {
        CdcSourceEntity source = requireSource(id);
        if (source.getConnectorName() != null) {
            try {
                kafkaConnectClient.delete(source.getConnectorName());
            } catch (Exception ignored) {
                // best-effort: Kafka Connect being unreachable shouldn't block removing the local row
            }
        }
        cdcSourceMapper.deleteById(id);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('realtime:cdc:start')")
    public ApiResponse<CdcSourceEntity> start(@PathVariable Long id) {
        CdcSourceEntity source = requireSource(id);
        environmentGuard.requirePermissionForEnvironment(source.getEnvironment());
        String connectorName = source.getConnectorName() != null ? source.getConnectorName() : "cdc-source-" + source.getId();
        try {
            kafkaConnectClient.deploy(connectorName, configBuilder.build(source, requireCdcDataSource(source.getDataSourceId())));
            // deploy() (PUT .../config) only (re)applies configuration - Kafka
            // Connect tracks paused/running as a separate flag that a config
            // update does NOT clear, so restarting a previously-stopped
            // source needs an explicit resume() too, or the DB ends up
            // recording RUNNING while the connector stays truly PAUSED
            // (confirmed live while testing the orphaned-connector cleanup
            // feature: a stop() + start() round trip left the connector
            // paused despite this endpoint reporting success).
            kafkaConnectClient.resume(connectorName);
            source.setConnectorName(connectorName);
            source.setStatus("RUNNING");
            source.setLastError(null);
            if ("ALERTING".equals(source.getAlertState())) {
                realtimeAlertService.notifyRecovery(
                    new RealtimeAlertService.AlertSubject("CDC_SOURCE", source.getId(), source.getName(), "CONNECTOR_FAILURE"),
                    source.getOwner(),
                    "CDC 数据源已恢复：" + source.getName(),
                    frontendUrl + "/realtime/cdc-sources"
                );
                source.setAlertState("OK");
            }
            // A manual (re)start doesn't go through CdcSourceStatusScheduler's
            // own FAILED->RUNNING detection, so its recovery state wouldn't
            // otherwise be cleared - a later, unrelated failure would be
            // wrongly treated as still mid-incident (wrong tier/circuit state).
            recoveryOrchestrator.recordRecovered("CDC_SOURCE", source.getId(), source.getName());
        } catch (ResponseStatusException exception) {
            source.setStatus("FAILED");
            source.setLastError(exception.getReason());
        }
        // updateById() silently skips null fields (MyBatis-Plus's default
        // NOT_NULL update strategy) - fine for update()'s "don't touch
        // fields the edit form didn't send" use just below, but wrong here:
        // clearing lastError to null on a successful (re)start needs to
        // actually reach the database, or the old error text lingers next
        // to a healthy RUNNING status forever. A targeted UpdateWrapper
        // listing only the fields this endpoint owns also means it can't
        // stomp on lag fields CdcSourceStatusScheduler manages.
        cdcSourceMapper.update(null, new LambdaUpdateWrapper<CdcSourceEntity>()
            .eq(CdcSourceEntity::getId, source.getId())
            .set(CdcSourceEntity::getConnectorName, source.getConnectorName())
            .set(CdcSourceEntity::getStatus, source.getStatus())
            .set(CdcSourceEntity::getLastError, source.getLastError())
            .set(CdcSourceEntity::getAlertState, source.getAlertState()));
        return ApiResponse.ok(source);
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('realtime:cdc:stop')")
    public ApiResponse<ActionResult> stop(@PathVariable Long id) {
        CdcSourceEntity source = requireSource(id);
        environmentGuard.requirePermissionForEnvironment(source.getEnvironment());
        if (source.getConnectorName() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "还没有启动过，无需停止");
        }
        ChangeApprovalService.GateResult gate = changeApprovalService.gate(
            ChangeApprovalService.ActionType.CDC_SOURCE_STOP, id, source.getEnvironment(), "CDC 数据源: " + source.getName());
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        applyStop(id);
        return ApiResponse.ok(ActionResult.applied());
    }

    private void applyStop(Long id) {
        CdcSourceEntity source = requireSource(id);
        kafkaConnectClient.pause(source.getConnectorName());
        source.setStatus("PAUSED");
        cdcSourceMapper.updateById(source);
        // Pausing is an intentional non-failure state - don't leave a stale
        // recovery-attempt count hanging around for it, but don't log it as
        // a "RECOVERED" event either since nothing was actually recovered.
        recoveryOrchestrator.reset("CDC_SOURCE", source.getId(), source.getName(), "手动停止，清除恢复状态");
    }

    @GetMapping("/{id}/status")
    @PreAuthorize("hasAuthority('realtime:cdc:view')")
    public ApiResponse<CdcSourceEntity> refreshStatus(@PathVariable Long id) {
        CdcSourceEntity source = requireSource(id);
        if (source.getConnectorName() == null) {
            return ApiResponse.ok(source);
        }
        KafkaConnectClient.ConnectorStatus status = kafkaConnectClient.status(source.getConnectorName());
        source.setStatus(status.state());
        source.setLastError(status.message());
        // Same null-skip issue as start() - KafkaConnectClient.status()
        // returns a null message for a healthy RUNNING connector, and a
        // plain updateById() would silently drop that clear instead of
        // writing it, so a source that recovered on its own (fixed without
        // going through this app's "启动" button) would still show its old
        // error text after a manual "状态" refresh.
        cdcSourceMapper.update(null, new LambdaUpdateWrapper<CdcSourceEntity>()
            .eq(CdcSourceEntity::getId, source.getId())
            .set(CdcSourceEntity::getStatus, source.getStatus())
            .set(CdcSourceEntity::getLastError, source.getLastError()));
        return ApiResponse.ok(source);
    }

    @GetMapping("/{id}/tables")
    @PreAuthorize("hasAuthority('realtime:cdc:view')")
    public ApiResponse<List<String>> tables(@PathVariable Long id) {
        return ApiResponse.ok(cdcTableSchemaService.listTables(requireSource(id)));
    }

    @GetMapping("/{id}/table-ddl")
    @PreAuthorize("hasAuthority('realtime:cdc:view')")
    public ApiResponse<CdcTableSchemaService.TableDdl> tableDdl(@PathVariable Long id, @RequestParam String table) {
        CdcSourceEntity source = requireSource(id);
        return ApiResponse.ok(cdcTableSchemaService.describeTable(source, requireCdcDataSource(source.getDataSourceId()), table));
    }

    @GetMapping("/{id}/sink-ddl")
    @PreAuthorize("hasAuthority('realtime:cdc:view')")
    public ApiResponse<String> sinkDdl(@PathVariable Long id, @RequestParam String table, @RequestParam Long targetDataSourceId, @RequestParam String targetTable) {
        CdcSourceEntity source = requireSource(id);
        DataSourceEntity cdcDataSource = requireCdcDataSource(source.getDataSourceId());
        DataSourceEntity target = dataSourceMapper.selectById(targetDataSourceId);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选目标数据源不存在");
        }
        return ApiResponse.ok(sinkTableDdlBuilder.build(source, cdcDataSource, table, target, targetTable));
    }

    private CdcSourceEntity requireSource(Long id) {
        CdcSourceEntity source = cdcSourceMapper.selectById(id);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CDC 数据源不存在");
        }
        return source;
    }

    private DataSourceEntity requireCdcDataSource(Long dataSourceId) {
        DataSourceEntity dataSource = dataSourceMapper.selectById(dataSourceId);
        if (dataSource == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选数据源不存在");
        }
        String type = dataSource.getType() == null ? "" : dataSource.getType().toUpperCase(java.util.Locale.ROOT);
        if (!"MYSQL".equals(type) && !"ORACLE".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CDC 数据源目前只支持 MySQL/Oracle 类型的数据源");
        }
        return dataSource;
    }
}
