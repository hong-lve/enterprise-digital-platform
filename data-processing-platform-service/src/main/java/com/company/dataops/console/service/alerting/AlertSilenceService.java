package com.company.dataops.console.service.alerting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.AlertSilenceWindowEntity;
import com.company.dataops.console.mapper.AlertSilenceWindowMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tier 3 item 3 of the reliability roadmap ("静默窗口") - suppresses
 * webhook/in-app delivery for a maintenance window without touching
 * alert_history (RealtimeAlertService still records every transition; only
 * the *notification* is skipped, so the audit timeline stays complete).
 */
@Component
public class AlertSilenceService {
    private final AlertSilenceWindowMapper alertSilenceWindowMapper;

    public AlertSilenceService(AlertSilenceWindowMapper alertSilenceWindowMapper) {
        this.alertSilenceWindowMapper = alertSilenceWindowMapper;
    }

    /**
     * True if there's an active window covering right now that matches this
     * entity - either a global silence (entityType null), a whole-type
     * silence (entityType set, entityId null), or this exact entity.
     */
    public boolean isSilenced(String entityType, Long entityId) {
        LocalDateTime now = LocalDateTime.now();
        Long count = alertSilenceWindowMapper.selectCount(new LambdaQueryWrapper<AlertSilenceWindowEntity>()
            .le(AlertSilenceWindowEntity::getStartsAt, now)
            .ge(AlertSilenceWindowEntity::getEndsAt, now)
            .and(outer -> outer
                .isNull(AlertSilenceWindowEntity::getEntityType)
                .or(w -> w.eq(AlertSilenceWindowEntity::getEntityType, entityType).isNull(AlertSilenceWindowEntity::getEntityId))
                .or(w -> w.eq(AlertSilenceWindowEntity::getEntityType, entityType).eq(AlertSilenceWindowEntity::getEntityId, entityId))));
        return count != null && count > 0;
    }

    public List<AlertSilenceWindowEntity> upcoming() {
        return alertSilenceWindowMapper.selectList(new LambdaQueryWrapper<AlertSilenceWindowEntity>()
            .ge(AlertSilenceWindowEntity::getEndsAt, LocalDateTime.now())
            .orderByAsc(AlertSilenceWindowEntity::getStartsAt));
    }

    public AlertSilenceWindowEntity create(AlertSilenceWindowEntity window) {
        alertSilenceWindowMapper.insert(window);
        return window;
    }

    public void delete(Long id) {
        alertSilenceWindowMapper.deleteById(id);
    }
}
