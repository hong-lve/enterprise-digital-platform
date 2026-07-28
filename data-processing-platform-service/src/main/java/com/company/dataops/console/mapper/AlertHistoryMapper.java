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
}
