package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.RecoveryEventEntity;
import com.company.dataops.console.entity.RecoveryStateEntity;
import com.company.dataops.console.service.recovery.RecoveryOrchestrator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared read/manual-takeover surface for RecoveryOrchestrator's persistent
 * tiered-retry/circuit-breaker state - one controller for both CDC sources
 * and Flink jobs since the underlying state machine is entity-type-agnostic
 * (see RecoveryOrchestrator). Permission is checked manually per entityType
 * (mirrors EnvironmentGuard's own SecurityContextHolder-based check) rather
 * than a single static @PreAuthorize, since the two entity types are gated by
 * two different existing permission points (realtime:cdc:* vs
 * realtime:flink:*) - same as every other page-specific action in this app
 * being scoped to its own page's permission rather than a shared one.
 */
@RestController
@RequestMapping("/realtime/recovery")
public class RecoveryController {
    private static final Map<String, String> VIEW_PERMISSION_BY_ENTITY_TYPE = Map.of(
        "CDC_SOURCE", "realtime:cdc:view",
        "FLINK_JOB", "realtime:flink:view"
    );
    private static final Map<String, String> MANAGE_PERMISSION_BY_ENTITY_TYPE = Map.of(
        "CDC_SOURCE", "realtime:cdc:recovery-manage",
        "FLINK_JOB", "realtime:flink:recovery-manage"
    );

    private final RecoveryOrchestrator recoveryOrchestrator;

    public RecoveryController(RecoveryOrchestrator recoveryOrchestrator) {
        this.recoveryOrchestrator = recoveryOrchestrator;
    }

    @GetMapping("/{entityType}/{entityId}")
    public ApiResponse<RecoveryStatusResponse> status(@PathVariable String entityType, @PathVariable Long entityId) {
        requirePermission(entityType, VIEW_PERMISSION_BY_ENTITY_TYPE);
        RecoveryStateEntity state = recoveryOrchestrator.state(entityType, entityId);
        List<RecoveryEventEntity> timeline = recoveryOrchestrator.timeline(entityType, entityId);
        return ApiResponse.ok(new RecoveryStatusResponse(state, timeline));
    }

    @PostMapping("/{entityType}/{entityId}/manual-takeover")
    public ApiResponse<Void> manualTakeover(@PathVariable String entityType, @PathVariable Long entityId, @RequestParam(required = false) String entityName) {
        requirePermission(entityType, MANAGE_PERMISSION_BY_ENTITY_TYPE);
        String operator = SecurityContextHolder.getContext().getAuthentication().getName();
        recoveryOrchestrator.manualTakeover(entityType, entityId, entityName, operator);
        return ApiResponse.ok();
    }

    private void requirePermission(String entityType, Map<String, String> permissionByEntityType) {
        String requiredPermission = permissionByEntityType.get(entityType);
        if (requiredPermission == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的 entityType：" + entityType);
        }
        boolean hasPermission = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(authority -> requiredPermission.equals(authority.getAuthority()));
        if (!hasPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要权限：" + requiredPermission);
        }
    }

    public record RecoveryStatusResponse(RecoveryStateEntity state, List<RecoveryEventEntity> timeline) {
    }
}
