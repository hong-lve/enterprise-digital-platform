package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.ContainerEventEntity;
import com.company.dataops.console.entity.ContainerStatusEntity;
import com.company.dataops.console.mapper.ContainerEventMapper;
import com.company.dataops.console.mapper.ContainerStatusMapper;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view over ContainerMonitoringScheduler's polled snapshots -
 * which service crashed/restarted how many times, and what node it's on.
 * Nothing here can start/stop/remove a container; see DockerApiClient's own
 * javadoc for why this stays GET-only end to end.
 */
@RestController
@RequestMapping("/realtime/container-monitoring")
public class ContainerMonitoringController {
    private final ContainerStatusMapper containerStatusMapper;
    private final ContainerEventMapper containerEventMapper;

    public ContainerMonitoringController(ContainerStatusMapper containerStatusMapper, ContainerEventMapper containerEventMapper) {
        this.containerStatusMapper = containerStatusMapper;
        this.containerEventMapper = containerEventMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:container:view')")
    public ApiResponse<List<ContainerStatusEntity>> list() {
        List<ContainerStatusEntity> containers = containerStatusMapper.selectList(new LambdaQueryWrapper<ContainerStatusEntity>()
            .orderByAsc(ContainerStatusEntity::getContainerName));
        return ApiResponse.ok(containers);
    }

    @GetMapping("/{name}/events")
    @PreAuthorize("hasAuthority('realtime:container:view')")
    public ApiResponse<List<ContainerEventEntity>> events(@PathVariable String name, @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<ContainerEventEntity> events = containerEventMapper.selectList(new LambdaQueryWrapper<ContainerEventEntity>()
            .eq(ContainerEventEntity::getContainerName, name)
            .orderByDesc(ContainerEventEntity::getOccurredAt)
            .last("limit " + safeLimit));
        return ApiResponse.ok(events);
    }
}
