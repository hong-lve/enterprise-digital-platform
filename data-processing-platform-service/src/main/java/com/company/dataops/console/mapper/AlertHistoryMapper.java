package com.company.dataops.console.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.dataops.console.entity.AlertHistoryEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AlertHistoryMapper extends BaseMapper<AlertHistoryEntity> {
    /**
     * Hourly ALERTING-transition counts since {@code since} - LambdaQueryWrapper
     * has no GROUP BY DSL, so this is a raw @Select (the only one in this
     * mapper). Returns Map<String,Object> rather than a typed record since
     * automatic record-constructor mapping isn't used anywhere else in this
     * codebase - AlertHistoryController.trend() does the casts. Only returns
     * hours that had >=1 transition; the caller zero-fills the rest.
     */
    @Select("SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:00:00') AS bucket, COUNT(*) AS cnt "
        + "FROM alert_history WHERE state = 'ALERTING' AND occurred_at >= #{since} "
        + "GROUP BY bucket ORDER BY bucket")
    List<Map<String, Object>> selectAlertingCountByHour(@Param("since") LocalDateTime since);

    /**
     * For AlertEscalationScheduler: each entity's own *latest* alert_history
     * row (self-join on MAX(occurred_at) per entity_type+entity_id, since
     * LambdaQueryWrapper has no "latest per group" DSL), filtered to ones
     * still ALERTING, not yet escalated, and old enough to cross the
     * escalation threshold.
     *
     * The cutoff is computed with MySQL's own NOW() rather than a
     * Java-side LocalDateTime.now() passed in as a parameter -
     * occurred_at comes from this same column's DEFAULT CURRENT_TIMESTAMP
     * (the DB server's clock), and the app server's clock isn't guaranteed
     * to agree with it (confirmed live: the MySQL container runs on UTC
     * while the app host runs on UTC+8 - an app-side "now" would make every
     * alert look ~8 hours overdue for escalation the instant it's created).
     * Keeping both sides of the comparison on the DB's own clock sidesteps
     * that entirely, regardless of whether the container ever gets its
     * timezone fixed.
     */
    @Select("SELECT h.* FROM alert_history h "
        + "INNER JOIN (SELECT entity_type, entity_id, MAX(occurred_at) AS max_occurred_at "
        + "  FROM alert_history GROUP BY entity_type, entity_id) latest "
        + "ON h.entity_type = latest.entity_type AND h.entity_id = latest.entity_id "
        + "  AND h.occurred_at = latest.max_occurred_at "
        + "WHERE h.state = 'ALERTING' AND h.escalated = 0 "
        + "  AND h.occurred_at <= DATE_SUB(NOW(), INTERVAL #{escalationMinutes} MINUTE)")
    List<AlertHistoryEntity> selectUnescalatedLongRunningAlerts(@Param("escalationMinutes") int escalationMinutes);
}
