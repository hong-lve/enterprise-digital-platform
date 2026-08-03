package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.DataQualityRuleEntity;
import com.company.dataops.console.entity.DataQualityViolationEntity;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataQualityRuleMapper;
import com.company.dataops.console.mapper.DataQualityViolationMapper;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.service.datasource.DataSourceConnectionService;
import com.company.dataops.console.service.datasource.SqlIdentifierValidator;
import com.company.dataops.console.service.query.QueryResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single-table data quality rules (see DataQualityRuleEntity) - unlike
 * DataReconciliationService, these check one table's own data against a
 * threshold rather than comparing source vs target. ruleType dispatches to
 * a different query per type; PK_DUPLICATE and VALUE_RANGE additionally
 * record the actual offending values into data_quality_violation (this
 * platform's stand-in for a DLQ/quarantine table for a JDBC source, which
 * has no message-bus-level dead-letter concept) so a human has something
 * concrete to inspect, not just a count.
 */
@Service
public class DataQualityRuleService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DataQualityRuleService.class);
    private static final int VIOLATION_SAMPLE_LIMIT = 100;

    private final DataQualityRuleMapper dataQualityRuleMapper;
    private final DataQualityViolationMapper dataQualityViolationMapper;
    private final DataSourceMapper dataSourceMapper;
    private final DataSourceConnectionService dataSourceConnectionService;
    private final RealtimeAlertService alertService;

    public DataQualityRuleService(
        DataQualityRuleMapper dataQualityRuleMapper,
        DataQualityViolationMapper dataQualityViolationMapper,
        DataSourceMapper dataSourceMapper,
        DataSourceConnectionService dataSourceConnectionService,
        RealtimeAlertService alertService
    ) {
        this.dataQualityRuleMapper = dataQualityRuleMapper;
        this.dataQualityViolationMapper = dataQualityViolationMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.dataSourceConnectionService = dataSourceConnectionService;
        this.alertService = alertService;
    }

    public void runAllEnabled() {
        dataQualityRuleMapper.selectList(null).stream()
            .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
            .forEach(rule -> {
                try {
                    runCheck(rule);
                } catch (Exception exception) {
                    LOGGER.warn("Data quality rule {} failed unexpectedly: {}", rule.getId(), exception.getMessage());
                }
            });
    }

    public DataQualityRuleEntity runCheck(DataQualityRuleEntity rule) {
        DataSourceEntity dataSource = dataSourceMapper.selectById(rule.getDataSourceId());
        if (dataSource == null) {
            return recordError(rule, "数据源不存在，可能已被删除");
        }
        if ("REDIS".equalsIgnoreCase(dataSource.getType())) {
            return recordError(rule, "数据质量规则不支持 Redis 数据源");
        }
        try {
            switch (rule.getRuleType().toUpperCase(Locale.ROOT)) {
                case "NULL_RATE" -> checkNullRate(rule, dataSource);
                case "UNIQUENESS" -> checkUniqueness(rule, dataSource);
                case "VALUE_RANGE" -> checkValueRange(rule, dataSource);
                case "PK_DUPLICATE" -> checkPkDuplicate(rule, dataSource);
                case "FRESHNESS" -> checkFreshness(rule, dataSource);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的规则类型：" + rule.getRuleType());
            }
            return rule;
        } catch (Exception exception) {
            return recordError(rule, exception.getMessage());
        }
    }

    private void checkNullRate(DataQualityRuleEntity rule, DataSourceEntity dataSource) {
        String table = SqlIdentifierValidator.requireValidTableName(rule.getTableName());
        String column = SqlIdentifierValidator.requireValidColumnName(rule.getColumnName(), "字段");
        Map<String, Object> row = firstRow(query(dataSource, rule, "SELECT COUNT(*) AS total, COUNT(" + column + ") AS non_null FROM " + table, 1));
        long total = toLong(row.get("total"));
        long nonNull = toLong(row.get("non_null"));
        double nullRate = total == 0 ? 0.0 : (double) (total - nonNull) / total;
        double threshold = rule.getThresholdMax() == null ? 0.0 : rule.getThresholdMax();
        applyResult(rule, nullRate > threshold, nullRate, null, null);
    }

    private void checkUniqueness(DataQualityRuleEntity rule, DataSourceEntity dataSource) {
        String table = SqlIdentifierValidator.requireValidTableName(rule.getTableName());
        String column = SqlIdentifierValidator.requireValidColumnName(rule.getColumnName(), "字段");
        Map<String, Object> row = firstRow(query(dataSource, rule, "SELECT COUNT(*) AS total, COUNT(DISTINCT " + column + ") AS distinct_count FROM " + table, 1));
        long total = toLong(row.get("total"));
        long distinctCount = toLong(row.get("distinct_count"));
        double duplicateRate = total == 0 ? 0.0 : (double) (total - distinctCount) / total;
        double threshold = rule.getThresholdMax() == null ? 0.0 : rule.getThresholdMax();
        applyResult(rule, duplicateRate > threshold, duplicateRate, null, null);
    }

    private void checkValueRange(DataQualityRuleEntity rule, DataSourceEntity dataSource) {
        String table = SqlIdentifierValidator.requireValidTableName(rule.getTableName());
        String column = SqlIdentifierValidator.requireValidColumnName(rule.getColumnName(), "字段");
        List<String> conditions = new ArrayList<>();
        // thresholdMin/thresholdMax are Double, not user-controlled strings -
        // Double.toString() only ever emits digits/./-/E, so splicing these
        // directly carries the same "already-validated numeric" safety as
        // the `int tolerance` field DataReconciliationService interpolates.
        if (rule.getThresholdMin() != null) {
            conditions.add(column + " < " + rule.getThresholdMin());
        }
        if (rule.getThresholdMax() != null) {
            conditions.add(column + " > " + rule.getThresholdMax());
        }
        if (conditions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALUE_RANGE 规则必须至少填写最小值或最大值");
        }
        String whereClause = String.join(" OR ", conditions);
        long violationCount = toLong(firstRow(query(dataSource, rule, "SELECT COUNT(*) AS violations FROM " + table + " WHERE " + whereClause, 1)).get("violations"));
        List<DataQualityViolationEntity> violations = List.of();
        if (violationCount > 0) {
            QueryResult sample = query(dataSource, rule, "SELECT " + column + " AS val FROM " + table + " WHERE " + whereClause, VIOLATION_SAMPLE_LIMIT);
            violations = sample.rows().stream().map(sampleRow -> violation(rule, String.valueOf(sampleRow.get("val")), "超出允许值域")).toList();
        }
        applyResult(rule, violationCount > 0, (double) violationCount, (int) violationCount, violations);
    }

    private void checkPkDuplicate(DataQualityRuleEntity rule, DataSourceEntity dataSource) {
        String table = SqlIdentifierValidator.requireValidTableName(rule.getTableName());
        String column = SqlIdentifierValidator.requireValidColumnName(rule.getColumnName(), "主键字段");
        QueryResult result = query(dataSource, rule,
            "SELECT " + column + " AS pk, COUNT(*) AS cnt FROM " + table + " GROUP BY " + column + " HAVING COUNT(*) > 1", VIOLATION_SAMPLE_LIMIT);
        List<DataQualityViolationEntity> violations = result.rows().stream()
            .map(row -> violation(rule, String.valueOf(row.get("pk")), "重复 " + row.get("cnt") + " 次"))
            .toList();
        applyResult(rule, !violations.isEmpty(), (double) violations.size(), violations.size(), violations);
    }

    private void checkFreshness(DataQualityRuleEntity rule, DataSourceEntity dataSource) {
        String table = SqlIdentifierValidator.requireValidTableName(rule.getTableName());
        String column = SqlIdentifierValidator.requireValidColumnName(rule.getColumnName(), "时间字段");
        Object latest = firstRow(query(dataSource, rule, "SELECT MAX(" + column + ") AS latest FROM " + table, 1)).get("latest");
        if (latest == null) {
            // No rows at all (or every value is null) - can't prove
            // freshness, so treat it the same as "too stale to tell".
            applyResult(rule, true, null, null, null);
            return;
        }
        long stalenessSeconds = (System.currentTimeMillis() - toEpochMillis(latest)) / 1000;
        double threshold = rule.getThresholdMax() == null ? Long.MAX_VALUE : rule.getThresholdMax();
        applyResult(rule, stalenessSeconds > threshold, (double) stalenessSeconds, null, null);
    }

    private DataQualityViolationEntity violation(DataQualityRuleEntity rule, String rowIdentifier, String detail) {
        DataQualityViolationEntity entity = new DataQualityViolationEntity();
        entity.setRuleId(rule.getId());
        entity.setRowIdentifier(rowIdentifier);
        entity.setDetail(detail);
        return entity;
    }

    private void applyResult(DataQualityRuleEntity rule, boolean violated, Double metricValue, Integer violationCount, List<DataQualityViolationEntity> violationRows) {
        String previousResult = rule.getLastResult();
        String newResult = violated ? "VIOLATION" : "OK";

        dataQualityRuleMapper.update(null, new LambdaUpdateWrapper<DataQualityRuleEntity>()
            .eq(DataQualityRuleEntity::getId, rule.getId())
            .set(DataQualityRuleEntity::getLastResult, newResult)
            .set(DataQualityRuleEntity::getLastMetricValue, metricValue)
            .set(DataQualityRuleEntity::getLastViolationCount, violationCount)
            .set(DataQualityRuleEntity::getLastCheckedAt, LocalDateTime.now())
            .set(DataQualityRuleEntity::getLastError, null));
        // Keep the in-memory object consistent with what was just persisted -
        // runCheck() returns this same instance to the "立即执行" caller, so
        // the immediate API response must reflect the fresh numbers, not
        // just lastResult, or a manual run looks like it didn't measure
        // anything until the next unrelated page refresh.
        rule.setLastResult(newResult);
        rule.setLastMetricValue(metricValue);
        rule.setLastViolationCount(violationCount);
        rule.setLastCheckedAt(LocalDateTime.now());
        rule.setLastError(null);

        if (violationRows != null) {
            // Cleared and rewritten fresh each run - only the latest run's
            // offending values matter, an ever-growing history isn't useful
            // for "go inspect what's wrong right now".
            dataQualityViolationMapper.delete(new LambdaQueryWrapper<DataQualityViolationEntity>().eq(DataQualityViolationEntity::getRuleId, rule.getId()));
            violationRows.forEach(dataQualityViolationMapper::insert);
        }

        RealtimeAlertService.AlertSubject subject = new RealtimeAlertService.AlertSubject("DATA_QUALITY_RULE", rule.getId(), rule.getName(), rule.getRuleType());
        String linkUrl = "/realtime/data-quality";
        if ("VIOLATION".equals(newResult) && !"VIOLATION".equals(previousResult)) {
            alertService.notifyFailure(subject, null, "数据质量规则触发：" + rule.getName(),
                String.format(Locale.ROOT, "指标值 %s，违规记录数 %s", metricValue == null ? "-" : metricValue, violationCount == null ? "-" : violationCount),
                linkUrl);
        } else if ("OK".equals(newResult) && "VIOLATION".equals(previousResult)) {
            alertService.notifyRecovery(subject, null, "数据质量规则恢复：" + rule.getName(), linkUrl);
        }
    }

    private DataQualityRuleEntity recordError(DataQualityRuleEntity rule, String message) {
        dataQualityRuleMapper.update(null, new LambdaUpdateWrapper<DataQualityRuleEntity>()
            .eq(DataQualityRuleEntity::getId, rule.getId())
            .set(DataQualityRuleEntity::getLastResult, "ERROR")
            .set(DataQualityRuleEntity::getLastError, message)
            .set(DataQualityRuleEntity::getLastCheckedAt, LocalDateTime.now()));
        rule.setLastResult("ERROR");
        rule.setLastError(message);
        return rule;
    }

    private QueryResult query(DataSourceEntity dataSource, DataQualityRuleEntity rule, String sql, int limit) {
        return dataSourceConnectionService.query(dataSource, rule.getDatabaseName(), sql, limit);
    }

    private Map<String, Object> firstRow(QueryResult result) {
        return result.rows().isEmpty() ? Map.of() : result.rows().get(0);
    }

    private long toLong(Object value) {
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }

    /** JDBC drivers hand back a mix of concrete date/time types depending on the driver - normalizes whichever one shows up to epoch millis. */
    private long toEpochMillis(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.time.ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant().toEpochMilli();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof java.util.Date date) {
            return date.getTime();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法解析时间字段的值类型：" + value.getClass().getSimpleName());
    }
}
