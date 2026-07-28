package com.company.dataops.console.service;

import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.entity.ReconciliationCheckEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.mapper.ReconciliationCheckMapper;
import com.company.dataops.console.service.datasource.DataSourceConnectionService;
import com.company.dataops.console.service.datasource.RedisConnectionService;
import com.company.dataops.console.service.query.QueryResult;
import java.time.LocalDateTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Compares row counts between a CDC source table and wherever it's been
 * mirrored to, catching the class of problem none of this platform's other
 * alerts can: a message silently dropped mid-stream, a sink write that
 * failed and got skipped, a connector resumed from the wrong offset after a
 * restart. All of those leave the connector "RUNNING" and the topic
 * "fresh" (CdcLagInspector's freshness proxy only tracks the latest
 * message's timestamp, not whether every message in between actually made
 * it through) - the only way to catch them is actually comparing how many
 * rows ended up on each side.
 *
 * Deliberately count-based, not row-by-row/checksum-based: a full row
 * comparison would mean streaming both tables through this JVM to diff
 * them, real I/O and memory cost for every check on every poll cycle. A
 * count mismatch can't prove exactly *which* rows are missing, but it's
 * enough to catch that something needs a human's attention - the existing
 * "实时数据查询" page is there for someone to actually go look once this
 * alert fires.
 */
@Service
public class DataReconciliationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataReconciliationService.class);

    private final ReconciliationCheckMapper reconciliationCheckMapper;
    private final DataSourceMapper dataSourceMapper;
    private final DataSourceConnectionService dataSourceConnectionService;
    private final RedisConnectionService redisConnectionService;
    private final RealtimeAlertService alertService;

    public DataReconciliationService(
        ReconciliationCheckMapper reconciliationCheckMapper,
        DataSourceMapper dataSourceMapper,
        DataSourceConnectionService dataSourceConnectionService,
        RedisConnectionService redisConnectionService,
        RealtimeAlertService alertService
    ) {
        this.reconciliationCheckMapper = reconciliationCheckMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.dataSourceConnectionService = dataSourceConnectionService;
        this.redisConnectionService = redisConnectionService;
        this.alertService = alertService;
    }

    public void runAllEnabled() {
        reconciliationCheckMapper.selectList(null).stream()
            .filter(check -> Boolean.TRUE.equals(check.getEnabled()))
            .forEach(check -> {
                try {
                    runCheck(check);
                } catch (Exception exception) {
                    // One check's own bug/timeout must never stop the rest
                    // from running - same "never let a scheduler poll cycle
                    // die partway through" posture as CdcSourceStatusScheduler.
                    LOGGER.warn("Reconciliation check {} failed unexpectedly: {}", check.getId(), exception.getMessage());
                }
            });
    }

    public ReconciliationCheckEntity runCheck(ReconciliationCheckEntity check) {
        DataSourceEntity source = dataSourceMapper.selectById(check.getSourceDataSourceId());
        DataSourceEntity target = dataSourceMapper.selectById(check.getTargetDataSourceId());
        if (source == null || target == null) {
            return recordError(check, "源或目标数据源不存在，可能已被删除");
        }
        try {
            long sourceCount = count(source, check.getSourceDatabase(), check.getSourceTable());
            long targetCount = count(target, check.getTargetDatabase(), check.getTargetTable());
            long diff = Math.abs(sourceCount - targetCount);
            int tolerance = check.getTolerance() == null ? 0 : check.getTolerance();
            String previousState = check.getLastState();
            String newState = diff > tolerance ? "DRIFT" : "OK";

            check.setLastSourceCount(sourceCount);
            check.setLastTargetCount(targetCount);
            check.setLastCheckedAt(LocalDateTime.now());
            check.setLastState(newState);
            check.setLastError(null);
            reconciliationCheckMapper.updateById(check);

            String linkUrl = "/realtime/reconciliation";
            RealtimeAlertService.AlertSubject subject = new RealtimeAlertService.AlertSubject(
                "RECONCILIATION", check.getId(), check.getName(), "ROW_COUNT_DRIFT");
            if ("DRIFT".equals(newState) && !"DRIFT".equals(previousState)) {
                alertService.notifyFailure(subject, null, "数据对账异常：" + check.getName(),
                    String.format(Locale.ROOT, "源表 %d 行，目标 %d 行，差异 %d（容忍 %d）", sourceCount, targetCount, diff, tolerance), linkUrl);
            } else if ("OK".equals(newState) && "DRIFT".equals(previousState)) {
                alertService.notifyRecovery(subject, null, "数据对账恢复：" + check.getName(), linkUrl);
            }
            return check;
        } catch (Exception exception) {
            return recordError(check, exception.getMessage());
        }
    }

    private ReconciliationCheckEntity recordError(ReconciliationCheckEntity check, String message) {
        check.setLastState("ERROR");
        check.setLastError(message);
        check.setLastCheckedAt(LocalDateTime.now());
        reconciliationCheckMapper.updateById(check);
        return check;
    }

    private long count(DataSourceEntity dataSource, String database, String tableOrPattern) {
        if ("REDIS".equalsIgnoreCase(dataSource.getType())) {
            return redisConnectionService.countKeysMatching(dataSource, database, tableOrPattern);
        }
        // Admin-configured (realtime:reconciliation:create/update), but
        // still validated as a bare identifier (optionally schema-qualified
        // for Oracle) before splicing into SQL text - same defense-in-depth
        // DataSourceConnectionService.tables()/columns() already apply to
        // admin-supplied identifiers elsewhere in this codebase, not a
        // trust boundary unique to this feature.
        if (!tableOrPattern.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的表名：" + tableOrPattern);
        }
        QueryResult result = dataSourceConnectionService.query(dataSource, database, "SELECT COUNT(*) AS cnt FROM " + tableOrPattern, 1);
        if (result.rows().isEmpty()) {
            return 0L;
        }
        Object value = result.rows().get(0).values().iterator().next();
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }
}
