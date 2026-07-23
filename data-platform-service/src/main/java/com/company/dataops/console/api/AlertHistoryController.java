package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.AlertHistoryEntity;
import com.company.dataops.console.mapper.AlertHistoryMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view over every alert transition RealtimeAlertService has
 * recorded (see AlertSubject) - independent of the entities' own
 * alert_state/backpressureAlertState/consumerLagAlertState columns, which
 * only ever hold the current state, not the history of how it got there.
 */
@RestController
@RequestMapping("/realtime/alert-history")
public class AlertHistoryController {
    private final AlertHistoryMapper alertHistoryMapper;

    public AlertHistoryController(AlertHistoryMapper alertHistoryMapper) {
        this.alertHistoryMapper = alertHistoryMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:alert-history:view')")
    public ApiResponse<PageResult<AlertHistoryEntity>> page(
        @RequestParam(defaultValue = "1") long current,
        @RequestParam(defaultValue = "10") long pageSize,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) Long entityId
    ) {
        LambdaQueryWrapper<AlertHistoryEntity> query = new LambdaQueryWrapper<AlertHistoryEntity>()
            .eq(entityType != null && !entityType.isBlank(), AlertHistoryEntity::getEntityType, entityType)
            .eq(entityId != null, AlertHistoryEntity::getEntityId, entityId)
            .orderByDesc(AlertHistoryEntity::getOccurredAt);
        Page<AlertHistoryEntity> page = alertHistoryMapper.selectPage(Page.of(current, pageSize), query);
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    private static final DateTimeFormatter HOUR_BUCKET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00");

    /**
     * Hourly count of ALERTING transitions over the trailing {@code hours}
     * window (default last 24h), for the overview page's trend chart. Always
     * returns exactly {@code hours} points, oldest first, zero-filled for
     * hours with no transitions - AlertHistoryMapper.selectAlertingCountByHour
     * only returns rows that had >=1 transition.
     */
    @GetMapping("/trend")
    @PreAuthorize("hasAuthority('realtime:alert-history:view')")
    public ApiResponse<List<AlertTrendPoint>> trend(@RequestParam(defaultValue = "24") int hours) {
        int safeHours = Math.max(1, Math.min(hours, 24 * 7)); // cheap guard against an unbounded loop/list below
        LocalDateTime sinceHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(safeHours - 1L);

        Map<String, Long> countsByHour = new HashMap<>();
        for (Map<String, Object> row : alertHistoryMapper.selectAlertingCountByHour(sinceHour)) {
            countsByHour.put(String.valueOf(row.get("bucket")), ((Number) row.get("cnt")).longValue());
        }

        List<AlertTrendPoint> points = new ArrayList<>();
        for (int i = 0; i < safeHours; i++) {
            String label = sinceHour.plusHours(i).format(HOUR_BUCKET_FORMAT);
            points.add(new AlertTrendPoint(label, countsByHour.getOrDefault(label, 0L)));
        }
        return ApiResponse.ok(points);
    }

    public record AlertTrendPoint(String hour, long count) {
    }
}
