package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.CdcSourceMapper;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.flink.FlinkClusterReconciliationService;
import com.company.dataops.console.service.kafka.CdcConnectorReconciliationService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/realtime")
public class RealtimeOverviewController {
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkSqlJobMapper flinkSqlJobMapper;
    private final CdcSourceMapper cdcSourceMapper;
    private final FlinkClusterReconciliationService flinkClusterReconciliationService;
    private final CdcConnectorReconciliationService cdcConnectorReconciliationService;

    public RealtimeOverviewController(
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkSqlJobMapper flinkSqlJobMapper,
        CdcSourceMapper cdcSourceMapper,
        FlinkClusterReconciliationService flinkClusterReconciliationService,
        CdcConnectorReconciliationService cdcConnectorReconciliationService
    ) {
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkSqlJobMapper = flinkSqlJobMapper;
        this.cdcSourceMapper = cdcSourceMapper;
        this.flinkClusterReconciliationService = flinkClusterReconciliationService;
        this.cdcConnectorReconciliationService = cdcConnectorReconciliationService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('realtime:overview:view')")
    public ApiResponse<OverviewView> overview() {
        // Job-failure, backpressure, and consumer lag are three separate
        // alert dimensions (see FlinkStreamJobPollingScheduler) - a job can
        // trip any subset of them independently, so all three are checked
        // here.
        List<AlertingItemView> alertingFlinkJobs = flinkStreamJobMapper.selectList(new LambdaQueryWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getAlertState, "ALERTING")
                .or()
                .eq(FlinkStreamJobEntity::getBackpressureAlertState, "ALERTING")
                .or()
                .eq(FlinkStreamJobEntity::getConsumerLagAlertState, "ALERTING"))
            .stream()
            .map(job -> new AlertingItemView(job.getId(), job.getName(), job.getOwner(), flinkAlertMessage(job)))
            .toList();

        // Same three alert dimensions as jar jobs (FlinkSqlJobPollingScheduler
        // mirrors FlinkStreamJobPollingScheduler's field semantics exactly).
        List<AlertingItemView> alertingSqlJobs = flinkSqlJobMapper.selectList(new LambdaQueryWrapper<FlinkSqlJobEntity>()
                .eq(FlinkSqlJobEntity::getAlertState, "ALERTING")
                .or()
                .eq(FlinkSqlJobEntity::getBackpressureAlertState, "ALERTING")
                .or()
                .eq(FlinkSqlJobEntity::getConsumerLagAlertState, "ALERTING"))
            .stream()
            .map(job -> new AlertingItemView(job.getId(), job.getName(), job.getOwner(), sqlJobAlertMessage(job)))
            .toList();

        // Message-staleness (lagSeconds) is shown on the CDC 数据源 page as a
        // plain number, not an alert dimension - see CdcSourceStatusScheduler's
        // checkLag() comment for why it's not trustworthy enough to escalate
        // (can't tell a stuck connector apart from a source table that just
        // hasn't been written to). Only real connector failure surfaces here.
        List<AlertingItemView> alertingCdcSources = cdcSourceMapper.selectList(new LambdaQueryWrapper<CdcSourceEntity>()
                .eq(CdcSourceEntity::getAlertState, "ALERTING"))
            .stream()
            .map(source -> new AlertingItemView(source.getId(), source.getName(), source.getOwner(), cdcAlertMessage(source)))
            .toList();

        return ApiResponse.ok(new OverviewView(
            alertingFlinkJobs,
            alertingSqlJobs,
            alertingCdcSources
        ));
    }

    @GetMapping("/flink-cluster/orphaned-jobs")
    @PreAuthorize("hasAuthority('realtime:overview:view')")
    public ApiResponse<List<FlinkClusterReconciliationService.OrphanedJobView>> orphanedJobs() {
        return ApiResponse.ok(flinkClusterReconciliationService.listOrphanedJobs());
    }

    @PostMapping("/flink-cluster/orphaned-jobs/{jobId}/cancel")
    @PreAuthorize("hasAuthority('realtime:overview:cancel-orphan')")
    public ApiResponse<Void> cancelOrphanedJob(@PathVariable String jobId) {
        flinkClusterReconciliationService.cancel(jobId);
        return ApiResponse.ok();
    }

    @GetMapping("/kafka-connect/orphaned-connectors")
    @PreAuthorize("hasAuthority('realtime:overview:view')")
    public ApiResponse<List<CdcConnectorReconciliationService.OrphanedConnectorView>> orphanedConnectors() {
        return ApiResponse.ok(cdcConnectorReconciliationService.listOrphanedConnectors());
    }

    @PostMapping("/kafka-connect/orphaned-connectors/{connectorName}/delete")
    @PreAuthorize("hasAuthority('realtime:overview:delete-orphan-connector')")
    public ApiResponse<Void> deleteOrphanedConnector(@PathVariable String connectorName) {
        cdcConnectorReconciliationService.delete(connectorName);
        return ApiResponse.ok();
    }

    private String flinkAlertMessage(FlinkStreamJobEntity job) {
        List<String> issues = new ArrayList<>();
        if ("ALERTING".equals(job.getAlertState())) {
            issues.add(job.getLastError());
        }
        if ("ALERTING".equals(job.getBackpressureAlertState())) {
            issues.add("反压过高（" + String.format("%.0f%%", job.getBackpressureRatio() * 100) + "）");
        }
        if ("ALERTING".equals(job.getConsumerLagAlertState())) {
            issues.add("消费延迟过高（积压 " + job.getConsumerLagRecords() + " 条）");
        }
        return String.join("；", issues);
    }

    private String sqlJobAlertMessage(FlinkSqlJobEntity job) {
        List<String> issues = new ArrayList<>();
        if ("ALERTING".equals(job.getAlertState())) {
            issues.add(job.getLastError());
        }
        if ("ALERTING".equals(job.getBackpressureAlertState())) {
            issues.add("反压过高（" + String.format("%.0f%%", job.getBackpressureRatio() * 100) + "）");
        }
        if ("ALERTING".equals(job.getConsumerLagAlertState())) {
            issues.add("消费延迟过高（积压 " + job.getConsumerLagRecords() + " 条）");
        }
        return String.join("；", issues);
    }

    private String cdcAlertMessage(CdcSourceEntity source) {
        return source.getLastError();
    }

    public record OverviewView(
        List<AlertingItemView> alertingFlinkJobs,
        List<AlertingItemView> alertingSqlJobs,
        List<AlertingItemView> alertingCdcSources
    ) {
    }

    public record AlertingItemView(Long id, String name, String owner, String message) {
    }
}
