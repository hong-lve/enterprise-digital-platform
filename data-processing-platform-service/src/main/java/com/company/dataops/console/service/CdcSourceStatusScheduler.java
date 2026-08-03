package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.mapper.CdcSourceMapper;
import com.company.dataops.console.service.kafka.CdcLagInspector;
import com.company.dataops.console.service.kafka.KafkaConnectClient;
import com.company.dataops.console.service.recovery.RecoveryOrchestrator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mirrors FlinkStreamJobPollingScheduler: a Debezium connector runs
 * indefinitely once deployed, so nothing else notices if it dies (e.g. the
 * source MySQL becomes unreachable) unless someone manually clicks the
 * "状态" refresh button. This periodically checks every source we think is
 * RUNNING or FAILED and syncs its real state back.
 *
 * Also computes message freshness (see CdcLagInspector) for connectors that
 * are still RUNNING at the Kafka Connect level, purely for display - it
 * can't tell a connector that's silently stopped apart from a source table
 * that just hasn't been written to in a while, so it's never escalated to
 * a notification or a failure state (see checkLag()'s comment).
 */
@Component
public class CdcSourceStatusScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CdcSourceStatusScheduler.class);

    private final CdcSourceMapper cdcSourceMapper;
    private final KafkaConnectClient kafkaConnectClient;
    private final CdcLagInspector cdcLagInspector;
    private final RecoveryOrchestrator recoveryOrchestrator;
    private final RealtimeAlertService realtimeAlertService;
    private final String frontendUrl;

    public CdcSourceStatusScheduler(
        CdcSourceMapper cdcSourceMapper,
        KafkaConnectClient kafkaConnectClient,
        CdcLagInspector cdcLagInspector,
        RecoveryOrchestrator recoveryOrchestrator,
        RealtimeAlertService realtimeAlertService,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.cdcSourceMapper = cdcSourceMapper;
        this.kafkaConnectClient = kafkaConnectClient;
        this.cdcLagInspector = cdcLagInspector;
        this.recoveryOrchestrator = recoveryOrchestrator;
        this.realtimeAlertService = realtimeAlertService;
        this.frontendUrl = frontendUrl;
    }

    @Scheduled(fixedDelay = 15000)
    public void pollRunningSources() {
        // FAILED sources have to stay in this query too, not just RUNNING
        // ones - otherwise a source that fails drops out of every future
        // poll forever (nothing left to notice it self-heal, and nothing
        // left to retry it). UNKNOWN (Connect unreachable, or the connector
        // was deleted outside this app) is deliberately left out - a human
        // can still recover it via the "状态" button, and attempting a
        // restart when Connect itself is unreachable would be pointless.
        //
        // Originally written as .in(..., "RUNNING", "FAILED") instead of
        // "not UNKNOWN" - status(), below, stores Kafka Connect's *raw* task
        // state verbatim whenever the connector exists (only the literal
        // sentinel "UNKNOWN" means Connect itself was unreachable), so any
        // other transient Kafka Connect state (e.g. "UNASSIGNED" during a
        // worker rebalance right after kafka-connect restarts) got persisted
        // once by this same method and then silently excluded from every
        // future poll forever - confirmed live: cdc-source-1 sat showing
        // "UNASSIGNED" for over 12 hours while the connector itself had long
        // since become genuinely RUNNING again, because this exact query
        // stopped selecting it the moment that one non-RUNNING/FAILED value
        // got written.
        List<CdcSourceEntity> sourcesToCheck = cdcSourceMapper.selectList(new LambdaQueryWrapper<CdcSourceEntity>()
            .ne(CdcSourceEntity::getStatus, "UNKNOWN")
            .isNotNull(CdcSourceEntity::getConnectorName));
        for (CdcSourceEntity source : sourcesToCheck) {
            boolean wasFailed = "FAILED".equals(source.getStatus());
            KafkaConnectClient.ConnectorStatus status = kafkaConnectClient.status(source.getConnectorName());

            if (!"RUNNING".equals(status.state())) {
                source.setStatus(status.state());
                source.setLastError(status.message());
                if ("FAILED".equals(status.state())) {
                    if (!wasFailed) {
                        recoveryOrchestrator.recordFailureDetected("CDC_SOURCE", source.getId(), source.getName(), source.getLastError());
                    }
                    if (!"ALERTING".equals(source.getAlertState())) {
                        realtimeAlertService.notifyFailure(
                            new RealtimeAlertService.AlertSubject("CDC_SOURCE", source.getId(), source.getName(), "CONNECTOR_FAILURE"),
                            source.getOwner(),
                            "CDC 数据源失败：" + source.getName(),
                            source.getLastError(),
                            frontendUrl + "/realtime/cdc-sources"
                        );
                        source.setAlertState("ALERTING");
                    }
                    attemptRecovery(source);
                }
                persistConnectorState(source);
                continue; // not healthy right now - "how stale is it" isn't a meaningful question
            }

            if (wasFailed) {
                // FAILED -> RUNNING: either self-healed or our own restart
                // attempt worked. This transition was never detected before -
                // a failed source used to drop out of the query above and
                // nothing ever looked at it again.
                recoveryOrchestrator.recordRecovered("CDC_SOURCE", source.getId(), source.getName());
                if ("ALERTING".equals(source.getAlertState())) {
                    realtimeAlertService.notifyRecovery(
                        new RealtimeAlertService.AlertSubject("CDC_SOURCE", source.getId(), source.getName(), "CONNECTOR_FAILURE"),
                        source.getOwner(),
                        "CDC 数据源已恢复：" + source.getName(),
                        frontendUrl + "/realtime/cdc-sources"
                    );
                    source.setAlertState("OK");
                }
                source.setStatus("RUNNING");
                source.setLastError(null);
                persistConnectorState(source);
            }
            checkLag(source);
        }
    }

    /**
     * Unlike Flink (see FlinkStreamJobPollingScheduler's comment on the same
     * pattern), Kafka Connect does NOT retry unhandled task exceptions by
     * default - errors.tolerance defaults to "none" and
     * DebeziumConnectorConfigBuilder doesn't set errors.retry.timeout/
     * errors.tolerance, so most failure types here (bad table/DB reference,
     * permission errors, connection loss that outlasts Debezium's own small
     * reconnect budget) reach FAILED on the first unhandled exception with
     * nothing having retried it yet. This is this layer's substitute for
     * what Flink's restartStrategy gives it for free - bounded, spaced-out
     * attempts via CdcRecoveryTracker, not a blind infinite retry loop.
     */
    private void attemptRecovery(CdcSourceEntity source) {
        if (recoveryOrchestrator.shouldAttempt("CDC_SOURCE", source.getId())) {
            recoveryOrchestrator.recordAttempt("CDC_SOURCE", source.getId(), source.getName());
            try {
                kafkaConnectClient.restart(source.getConnectorName());
            } catch (Exception exception) {
                LOGGER.warn("Best-effort restart failed for CDC source {} ({}): {}", source.getId(), source.getConnectorName(), exception.getMessage());
            }
        }
        // Whether this call just tripped the circuit or it was already
        // tripped from an earlier poll, "needs manual intervention" is
        // communicated via recovery_state (see the recovery drawer), not by
        // growing lastError with a repeated suffix every 15s poll tick.
    }

    // Same null-skip pitfall CdcSourceController.start()/refreshStatus() already
    // work around: MyBatis-Plus updateById() silently skips null fields (its
    // default NOT_NULL strategy), so clearing lastError to null on a recovery
    // needs a targeted UpdateWrapper or the old error text lingers forever
    // next to a healthy RUNNING status (see task history - this exact bug was
    // hit and fixed once already for CdcSourceController).
    private void persistConnectorState(CdcSourceEntity source) {
        cdcSourceMapper.update(null, new LambdaUpdateWrapper<CdcSourceEntity>()
            .eq(CdcSourceEntity::getId, source.getId())
            .set(CdcSourceEntity::getStatus, source.getStatus())
            .set(CdcSourceEntity::getLastError, source.getLastError())
            .set(CdcSourceEntity::getAlertState, source.getAlertState()));
    }

    // Deliberately not an alert dimension: "距最近一条消息过去了多久" can't tell
    // a genuinely stuck connector apart from a source table that just hasn't
    // been written to in a while (see CdcLagInspector's own javadoc) - taking
    // the max across every table in tableIncludeList makes that worse, since
    // one rarely-updated table drags the whole source's number up even while
    // every other table is flowing normally. Still computed and shown as a
    // plain number for visibility, just never escalated to a notification or
    // treated as a failure state.
    private void checkLag(CdcSourceEntity source) {
        Long maxLagSeconds = null;
        for (String tableRef : source.getTableIncludeList().split(",")) {
            String topic = source.getTopicPrefix() + "." + tableRef.trim();
            Long freshness = cdcLagInspector.freshnessSeconds(topic);
            if (freshness != null && (maxLagSeconds == null || freshness > maxLagSeconds)) {
                maxLagSeconds = freshness;
            }
        }
        // Targeted column update, not updateById(source) - source was loaded
        // at the top of pollRunningSources() and this method's Kafka polling
        // can take a few seconds, so a blanket full-entity write here would
        // clobber any other field someone else changed (e.g. via the edit
        // form) in that window back to its stale snapshot value.
        cdcSourceMapper.update(null, new LambdaUpdateWrapper<CdcSourceEntity>()
            .eq(CdcSourceEntity::getId, source.getId())
            .set(CdcSourceEntity::getLagSeconds, maxLagSeconds));
    }
}
