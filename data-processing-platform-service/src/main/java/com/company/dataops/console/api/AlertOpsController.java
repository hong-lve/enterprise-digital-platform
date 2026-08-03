package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.AlertRetryQueueEntity;
import com.company.dataops.console.entity.AlertSilenceWindowEntity;
import com.company.dataops.console.entity.OnCallScheduleEntity;
import com.company.dataops.console.service.alerting.AlertRetryQueueService;
import com.company.dataops.console.service.alerting.AlertSilenceService;
import com.company.dataops.console.service.alerting.OnCallService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tier 3 item 3 of the reliability roadmap ("值班人和静默窗口") - management
 * surface for OnCallService/AlertSilenceService's schedule tables, plus a
 * read-only view of AlertRetryQueueService's delivery-retry queue. Actual
 * paging/silencing/retrying logic lives in those services and in
 * RealtimeAlertService/AlertRetryScheduler/AlertEscalationScheduler - this
 * controller is just CRUD + listing.
 */
@RestController
@RequestMapping("/realtime/alert-ops")
public class AlertOpsController {
    private final OnCallService onCallService;
    private final AlertSilenceService alertSilenceService;
    private final AlertRetryQueueService alertRetryQueueService;

    public AlertOpsController(OnCallService onCallService, AlertSilenceService alertSilenceService, AlertRetryQueueService alertRetryQueueService) {
        this.onCallService = onCallService;
        this.alertSilenceService = alertSilenceService;
        this.alertRetryQueueService = alertRetryQueueService;
    }

    @GetMapping("/on-call")
    @PreAuthorize("hasAuthority('realtime:oncall:view')")
    public ApiResponse<OnCallResponse> onCall() {
        return ApiResponse.ok(new OnCallResponse(onCallService.currentOnCall(), onCallService.upcoming()));
    }

    @PostMapping("/on-call")
    @PreAuthorize("hasAuthority('realtime:oncall:manage')")
    public ApiResponse<OnCallScheduleEntity> createShift(@Valid @RequestBody OnCallScheduleEntity shift) {
        shift.setId(null);
        shift.setCreatedBy(currentUsername());
        return ApiResponse.ok(onCallService.create(shift));
    }

    @DeleteMapping("/on-call/{id}")
    @PreAuthorize("hasAuthority('realtime:oncall:manage')")
    public ApiResponse<Void> deleteShift(@PathVariable Long id) {
        onCallService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/silence-windows")
    @PreAuthorize("hasAuthority('realtime:oncall:view')")
    public ApiResponse<List<AlertSilenceWindowEntity>> silenceWindows() {
        return ApiResponse.ok(alertSilenceService.upcoming());
    }

    @PostMapping("/silence-windows")
    @PreAuthorize("hasAuthority('realtime:oncall:manage')")
    public ApiResponse<AlertSilenceWindowEntity> createSilenceWindow(@Valid @RequestBody AlertSilenceWindowEntity window) {
        window.setId(null);
        window.setCreatedBy(currentUsername());
        return ApiResponse.ok(alertSilenceService.create(window));
    }

    @DeleteMapping("/silence-windows/{id}")
    @PreAuthorize("hasAuthority('realtime:oncall:manage')")
    public ApiResponse<Void> deleteSilenceWindow(@PathVariable Long id) {
        alertSilenceService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/retry-queue")
    @PreAuthorize("hasAuthority('realtime:oncall:view')")
    public ApiResponse<List<AlertRetryQueueEntity>> retryQueue() {
        return ApiResponse.ok(alertRetryQueueService.recent());
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public record OnCallResponse(String currentOnCall, List<OnCallScheduleEntity> upcoming) {
    }
}
