package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.entity.ReconciliationCheckEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.mapper.ReconciliationCheckMapper;
import com.company.dataops.console.service.datasource.DataSourceConnectionService;
import com.company.dataops.console.service.datasource.RedisConnectionService;
import com.company.dataops.console.service.datasource.SqlIdentifierValidator;
import com.company.dataops.console.service.query.QueryResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Compares a CDC source table against wherever it's been mirrored to,
 * catching the class of problem none of this platform's other alerts can: a
 * message silently dropped mid-stream, a sink write that failed and got
 * skipped, a connector resumed from the wrong offset after a restart. All of
 * those leave the connector "RUNNING" and the topic "fresh" - the only way
 * to catch them is comparing the two sides directly.
 *
 * Two check types (checkType on ReconciliationCheckEntity):
 * - ROW_COUNT (default, original behavior): COUNT(*) on each side.
 * - AGGREGATE: SUM(aggregateColumn) on each side - catches "same row count,
 *   wrong values" that ROW_COUNT completely misses (a sink write that
 *   corrupted a numeric column, a lossy type conversion, etc.).
 * Both types honor an optional partitionColumn: instead of one table-wide
 * number, groups by that column on each side and diffs per partition value,
 * so a drift can be pinned to which partition/day it's actually in.
 */
@Service
public class DataReconciliationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataReconciliationService.class);
    private static final String SINGLE_VALUE_KEY = "__all__";

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
        boolean isAggregate = "AGGREGATE".equalsIgnoreCase(check.getCheckType());
        if (isAggregate && (check.getAggregateColumn() == null || check.getAggregateColumn().isBlank())) {
            return recordError(check, "AGGREGATE 类型对账必须填写聚合字段");
        }
        boolean partitioned = check.getPartitionColumn() != null && !check.getPartitionColumn().isBlank();
        try {
            Map<String, Double> sourceMetrics = queryMetric(source, check.getSourceDatabase(), check.getSourceTable(),
                check.getAggregateColumn(), check.getPartitionColumn(), isAggregate);
            Map<String, Double> targetMetrics = queryMetric(target, check.getTargetDatabase(), check.getTargetTable(),
                check.getAggregateColumn(), check.getPartitionColumn(), isAggregate);

            double sourceTotal = sourceMetrics.values().stream().mapToDouble(Double::doubleValue).sum();
            double targetTotal = targetMetrics.values().stream().mapToDouble(Double::doubleValue).sum();
            double diff = Math.abs(sourceTotal - targetTotal);
            int tolerance = check.getTolerance() == null ? 0 : check.getTolerance();
            String previousState = check.getLastState();

            String partitionSummary = partitioned ? summarizePartitionDrift(sourceMetrics, targetMetrics, tolerance) : null;
            boolean hasPartitionDrift = partitionSummary != null && !partitionSummary.isBlank();
            String newState = diff > tolerance || hasPartitionDrift ? "DRIFT" : "OK";

            LambdaUpdateWrapper<ReconciliationCheckEntity> update = new LambdaUpdateWrapper<ReconciliationCheckEntity>()
                .eq(ReconciliationCheckEntity::getId, check.getId())
                .set(ReconciliationCheckEntity::getLastCheckedAt, LocalDateTime.now())
                .set(ReconciliationCheckEntity::getLastState, newState)
                .set(ReconciliationCheckEntity::getLastError, null)
                .set(ReconciliationCheckEntity::getPartitionDriftSummary, partitionSummary);
            if (isAggregate) {
                update.set(ReconciliationCheckEntity::getLastSourceAggregate, sourceTotal)
                    .set(ReconciliationCheckEntity::getLastTargetAggregate, targetTotal)
                    .set(ReconciliationCheckEntity::getLastSourceCount, null)
                    .set(ReconciliationCheckEntity::getLastTargetCount, null);
            } else {
                update.set(ReconciliationCheckEntity::getLastSourceCount, (long) sourceTotal)
                    .set(ReconciliationCheckEntity::getLastTargetCount, (long) targetTotal)
                    .set(ReconciliationCheckEntity::getLastSourceAggregate, null)
                    .set(ReconciliationCheckEntity::getLastTargetAggregate, null);
            }
            reconciliationCheckMapper.update(null, update);
            check.setLastState(newState);

            String linkUrl = "/realtime/reconciliation";
            RealtimeAlertService.AlertSubject subject = new RealtimeAlertService.AlertSubject(
                "RECONCILIATION", check.getId(), check.getName(), isAggregate ? "AGGREGATE_DRIFT" : "ROW_COUNT_DRIFT");
            if ("DRIFT".equals(newState) && !"DRIFT".equals(previousState)) {
                String detail = isAggregate
                    ? String.format(Locale.ROOT, "源聚合值 %.2f，目标聚合值 %.2f，差异 %.2f（容忍 %d）", sourceTotal, targetTotal, diff, tolerance)
                    : String.format(Locale.ROOT, "源表 %d 行，目标 %d 行，差异 %d（容忍 %d）", (long) sourceTotal, (long) targetTotal, (long) diff, tolerance);
                if (hasPartitionDrift) {
                    detail += "；分区差异：" + partitionSummary;
                }
                alertService.notifyFailure(subject, null, "数据对账异常：" + check.getName(), detail, linkUrl);
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

    /**
     * Returns a partition-value -> metric map (COUNT(*) or SUM(aggregateColumn)
     * depending on isAggregate) - a single entry keyed SINGLE_VALUE_KEY when
     * partitionColumn is blank, one entry per distinct partition value
     * otherwise.
     */
    private Map<String, Double> queryMetric(DataSourceEntity dataSource, String database, String tableOrPattern,
                                             String aggregateColumn, String partitionColumn, boolean isAggregate) {
        if ("REDIS".equalsIgnoreCase(dataSource.getType())) {
            if (isAggregate || (partitionColumn != null && !partitionColumn.isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Redis 目标不支持聚合值对账或分区级对账，只能用行数（Key 计数）对账");
            }
            return Map.of(SINGLE_VALUE_KEY, (double) redisConnectionService.countKeysMatching(dataSource, database, tableOrPattern));
        }
        String table = SqlIdentifierValidator.requireValidTableName(tableOrPattern);
        String selectExpr = isAggregate ? "SUM(" + SqlIdentifierValidator.requireValidColumnName(aggregateColumn, "聚合字段") + ")" : "COUNT(*)";
        if (partitionColumn == null || partitionColumn.isBlank()) {
            QueryResult result = dataSourceConnectionService.query(dataSource, database, "SELECT " + selectExpr + " AS metric FROM " + table, 1);
            return Map.of(SINGLE_VALUE_KEY, result.rows().isEmpty() ? 0.0 : toDouble(result.rows().get(0).get("metric")));
        }
        String partition = SqlIdentifierValidator.requireValidColumnName(partitionColumn, "分区字段");
        // Capped at 1000 distinct partition values - a partition-level check
        // is meant for a bounded dimension (a date column, a status enum),
        // not an arbitrarily high-cardinality one.
        QueryResult result = dataSourceConnectionService.query(dataSource, database,
            "SELECT " + partition + " AS part_key, " + selectExpr + " AS metric FROM " + table + " GROUP BY " + partition, 1000);
        Map<String, Double> byPartition = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : result.rows()) {
            byPartition.put(String.valueOf(row.get("part_key")), toDouble(row.get("metric")));
        }
        return byPartition;
    }

    private String summarizePartitionDrift(Map<String, Double> sourceMetrics, Map<String, Double> targetMetrics, int tolerance) {
        TreeSet<String> allKeys = new TreeSet<>();
        allKeys.addAll(sourceMetrics.keySet());
        allKeys.addAll(targetMetrics.keySet());
        List<String> drifted = new ArrayList<>();
        for (String key : allKeys) {
            double sourceValue = sourceMetrics.getOrDefault(key, 0.0);
            double targetValue = targetMetrics.getOrDefault(key, 0.0);
            if (Math.abs(sourceValue - targetValue) > tolerance) {
                drifted.add(String.format(Locale.ROOT, "%s(源%.2f/目标%.2f)", key, sourceValue, targetValue));
            }
        }
        if (drifted.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", drifted);
        return joined.length() > 950 ? joined.substring(0, 950) + "...(截断)" : joined;
    }

    private double toDouble(Object value) {
        return value == null ? 0.0 : Double.parseDouble(String.valueOf(value));
    }
}
