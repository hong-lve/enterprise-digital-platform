package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.JobVersionSnapshotEntity;
import com.company.dataops.console.service.versioning.JobVersionSnapshotService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only surface for JobVersionSnapshotService - one controller for both
 * job kinds since history/diff logic is entity-type-agnostic, mirrors
 * RecoveryController's shared-read/entity-specific-permission pattern.
 * Rollback stays in FlinkStreamJobController/FlinkSqlJobController since
 * applying one needs each entity type's own submission client/mapper.
 */
@RestController
@RequestMapping("/realtime/job-versions")
public class JobVersionController {
    private static final Map<String, String> VIEW_PERMISSION_BY_ENTITY_TYPE = Map.of(
        "FLINK_STREAM_JOB", "realtime:flink:view",
        "FLINK_SQL_JOB", "realtime:sql-job:view"
    );

    private final JobVersionSnapshotService jobVersionSnapshotService;

    public JobVersionController(JobVersionSnapshotService jobVersionSnapshotService) {
        this.jobVersionSnapshotService = jobVersionSnapshotService;
    }

    @GetMapping("/{entityType}/{entityId}")
    public ApiResponse<List<JobVersionSnapshotEntity>> history(@PathVariable String entityType, @PathVariable Long entityId) {
        requirePermission(entityType);
        return ApiResponse.ok(jobVersionSnapshotService.history(entityType, entityId));
    }

    @GetMapping("/{entityType}/{entityId}/diff")
    public ApiResponse<List<JobVersionSnapshotService.FieldChange>> diff(
        @PathVariable String entityType,
        @PathVariable Long entityId,
        @RequestParam int fromVersion,
        @RequestParam int toVersion
    ) {
        requirePermission(entityType);
        return ApiResponse.ok(jobVersionSnapshotService.diff(entityType, entityId, fromVersion, toVersion));
    }

    private void requirePermission(String entityType) {
        String requiredPermission = VIEW_PERMISSION_BY_ENTITY_TYPE.get(entityType);
        if (requiredPermission == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的 entityType：" + entityType);
        }
        boolean hasPermission = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(authority -> requiredPermission.equals(authority.getAuthority()));
        if (!hasPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要权限：" + requiredPermission);
        }
    }
}
