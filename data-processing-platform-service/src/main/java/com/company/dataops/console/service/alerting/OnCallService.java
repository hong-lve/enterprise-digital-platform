package com.company.dataops.console.service.alerting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.OnCallScheduleEntity;
import com.company.dataops.console.mapper.OnCallScheduleMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tier 3 item 3 of the reliability roadmap ("值班人") - explicit shift rows
 * rather than a recurring-rule engine, matching this project's general
 * preference for the simplest thing that actually works. RealtimeAlertService
 * pages currentOnCall() (if configured) on every ALERT, in addition to
 * whatever fixed "owner" a job/CDC source happens to have - the point of
 * on-call is catching things nobody in particular is watching, not just
 * duplicating the owner notification.
 */
@Component
public class OnCallService {
    private final OnCallScheduleMapper onCallScheduleMapper;

    public OnCallService(OnCallScheduleMapper onCallScheduleMapper) {
        this.onCallScheduleMapper = onCallScheduleMapper;
    }

    /** Username of whoever's shift covers this exact moment, or null if nobody's scheduled. */
    public String currentOnCall() {
        LocalDateTime now = LocalDateTime.now();
        OnCallScheduleEntity shift = onCallScheduleMapper.selectOne(new LambdaQueryWrapper<OnCallScheduleEntity>()
            .le(OnCallScheduleEntity::getStartsAt, now)
            .ge(OnCallScheduleEntity::getEndsAt, now)
            .orderByDesc(OnCallScheduleEntity::getId)
            .last("LIMIT 1"));
        return shift == null ? null : shift.getUsername();
    }

    public List<OnCallScheduleEntity> upcoming() {
        // "Upcoming" = anything that hasn't ended yet, including the
        // currently-active shift - a schedule page wants to show what's
        // happening now too, not just strictly future rows.
        return onCallScheduleMapper.selectList(new LambdaQueryWrapper<OnCallScheduleEntity>()
            .ge(OnCallScheduleEntity::getEndsAt, LocalDateTime.now())
            .orderByAsc(OnCallScheduleEntity::getStartsAt));
    }

    public OnCallScheduleEntity create(OnCallScheduleEntity shift) {
        onCallScheduleMapper.insert(shift);
        return shift;
    }

    public void delete(Long id) {
        onCallScheduleMapper.deleteById(id);
    }
}
