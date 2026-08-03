package com.company.dataops.console.service.alerting;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.AlertHistoryEntity;
import com.company.dataops.console.mapper.AlertHistoryMapper;
import com.company.dataops.console.service.RealtimeAlertService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tier 3 item 3 of the reliability roadmap ("告警升级") - if an entity has
 * been sitting in ALERTING for longer than the configured threshold with no
 * recovery, page the *current* on-call person again (re-resolved fresh, not
 * cached from when the alert first fired - if the shift rotated in the
 * meantime, the new on-call person is who should get the nag, not whoever
 * was on duty originally). Each unresolved incident escalates exactly once
 * (marked via alert_history.escalated) - it isn't re-escalated every tick,
 * and a fresh incident (new ALERTING/RECOVERY row) always starts unescalated.
 */
@Component
public class AlertEscalationScheduler {
    private final AlertHistoryMapper alertHistoryMapper;
    private final AlertSilenceService alertSilenceService;
    private final OnCallService onCallService;
    private final RealtimeAlertService realtimeAlertService;
    private final int escalationMinutes;
    private final String frontendUrl;

    public AlertEscalationScheduler(
        AlertHistoryMapper alertHistoryMapper,
        AlertSilenceService alertSilenceService,
        OnCallService onCallService,
        RealtimeAlertService realtimeAlertService,
        @Value("${platform.bigdata.alert-escalation-minutes:30}") int escalationMinutes,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.alertHistoryMapper = alertHistoryMapper;
        this.alertSilenceService = alertSilenceService;
        this.onCallService = onCallService;
        this.realtimeAlertService = realtimeAlertService;
        this.escalationMinutes = escalationMinutes;
        this.frontendUrl = frontendUrl;
    }

    @Scheduled(fixedDelay = 60000)
    public void escalateLongRunningAlerts() {
        List<AlertHistoryEntity> candidates = alertHistoryMapper.selectUnescalatedLongRunningAlerts(escalationMinutes);
        for (AlertHistoryEntity candidate : candidates) {
            // Silenced entities are skipped entirely this tick (not marked
            // escalated) so a still-unresolved alert genuinely escalates
            // once the silence window ends, instead of being permanently
            // skipped just because it happened to cross the threshold while
            // silenced.
            if (alertSilenceService.isSilenced(candidate.getEntityType(), candidate.getEntityId())) {
                continue;
            }
            String onCall = onCallService.currentOnCall();
            if (onCall != null) {
                realtimeAlertService.notifyMultiple(
                    List.of(onCall),
                    "【告警升级】" + candidate.getEntityName() + " 已持续告警超过 " + escalationMinutes + " 分钟未恢复",
                    candidate.getMessage(),
                    "ALERT_ESCALATION",
                    frontendUrl
                );
            }
            alertHistoryMapper.update(null, new LambdaUpdateWrapper<AlertHistoryEntity>()
                .eq(AlertHistoryEntity::getId, candidate.getId())
                .set(AlertHistoryEntity::getEscalated, true));
        }
    }
}
