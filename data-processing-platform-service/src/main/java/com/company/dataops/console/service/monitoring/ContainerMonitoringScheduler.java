package com.company.dataops.console.service.monitoring;

import com.company.dataops.console.service.coordination.ClusterSingleton;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.ContainerEventEntity;
import com.company.dataops.console.entity.ContainerStatusEntity;
import com.company.dataops.console.mapper.ContainerEventMapper;
import com.company.dataops.console.mapper.ContainerStatusMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls every container on the bigdata host (docker/bigdata's compose
 * stack, plus the kind-based Flink HA node containers - anything
 * `docker ps -a` sees) and keeps container_status/container_event up to
 * date. See ContainerStatusEntity's own javadoc for why a durable
 * cumulative_restart_count exists alongside Docker's own (recreation-reset)
 * RestartCount.
 *
 * Best-effort like the other pollers in this package (FlinkStreamJobPollingScheduler,
 * CdcSourceStatusScheduler): a failed poll just logs and tries again next
 * tick, since the Docker API is only reachable through an SSH tunnel that
 * can itself be briefly unavailable.
 */
@Component
public class ContainerMonitoringScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerMonitoringScheduler.class);
    private static final String DOCKER_NEVER_STARTED_SENTINEL = "0001-01-01T00:00:00Z";

    private final DockerApiClient dockerApiClient;
    private final ContainerStatusMapper containerStatusMapper;
    private final ContainerEventMapper containerEventMapper;
    private final String node;

    public ContainerMonitoringScheduler(
        DockerApiClient dockerApiClient,
        ContainerStatusMapper containerStatusMapper,
        ContainerEventMapper containerEventMapper,
        @Value("${platform.bigdata.docker-node-name:vultr-bigdata-server}") String node
    ) {
        this.dockerApiClient = dockerApiClient;
        this.containerStatusMapper = containerStatusMapper;
        this.containerEventMapper = containerEventMapper;
        this.node = node;
    }

    @ClusterSingleton(value = "container-monitoring", lockAtMostSeconds = 300)
    @Scheduled(fixedDelay = 30000)
    @SuppressWarnings("unchecked")
    public void poll() {
        List<Map<String, Object>> containers;
        try {
            containers = dockerApiClient.listContainers();
        } catch (Exception exception) {
            LOGGER.warn("Failed to list containers for monitoring: {}", exception.getMessage(), exception);
            return;
        }
        if (containers == null) {
            return;
        }
        for (Map<String, Object> summary : containers) {
            String id = (String) summary.get("Id");
            try {
                pollOne(id);
            } catch (Exception exception) {
                LOGGER.warn("Failed to inspect container {} for monitoring: {}", id, exception.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void pollOne(String id) {
        Map<String, Object> detail = dockerApiClient.inspectContainer(id);
        if (detail == null) {
            return;
        }
        String name = stripLeadingSlash((String) detail.get("Name"));
        Map<String, Object> state = (Map<String, Object>) detail.get("State");
        Map<String, Object> config = (Map<String, Object>) detail.get("Config");
        String dockerState = state == null ? "unknown" : String.valueOf(state.get("Status"));
        String image = config == null ? null : (String) config.get("Image");
        Integer restartCount = detail.get("RestartCount") == null ? 0 : ((Number) detail.get("RestartCount")).intValue();
        LocalDateTime startedAt = state == null ? null : parseDockerTimestamp((String) state.get("StartedAt"));

        ContainerStatusEntity existing = containerStatusMapper.selectOne(new LambdaQueryWrapper<ContainerStatusEntity>()
            .eq(ContainerStatusEntity::getContainerName, name));

        if (existing == null) {
            ContainerStatusEntity fresh = new ContainerStatusEntity();
            fresh.setContainerName(name);
            fresh.setNode(node);
            fresh.setImage(image);
            fresh.setState(dockerState);
            fresh.setStatusText(dockerState);
            fresh.setDockerRestartCount(restartCount);
            // First time we've ever seen this container - trust Docker's own
            // count as the starting point rather than 0, so history it
            // already has isn't silently dropped.
            fresh.setCumulativeRestartCount(restartCount);
            fresh.setLastContainerId(id);
            fresh.setStartedAt(startedAt);
            fresh.setLastPolledAt(LocalDateTime.now());
            containerStatusMapper.insert(fresh);
            return;
        }

        boolean recreated = !id.equals(existing.getLastContainerId());
        int previousRestartCount = existing.getDockerRestartCount() == null ? 0 : existing.getDockerRestartCount();
        boolean restartedInPlace = !recreated && restartCount > previousRestartCount;
        int cumulative = existing.getCumulativeRestartCount() == null ? restartCount : existing.getCumulativeRestartCount();

        if (recreated) {
            cumulative += 1;
            recordEvent(name, "RECREATED", "容器被重建（新的容器 ID），旧 ID=" + safeShortId(existing.getLastContainerId()) + "，新 ID=" + safeShortId(id));
        } else if (restartedInPlace) {
            cumulative += restartCount - previousRestartCount;
            recordEvent(name, "RESTART", "Docker 内部重启次数从 " + previousRestartCount + " 增加到 " + restartCount);
        } else if ("exited".equalsIgnoreCase(dockerState) && !"exited".equalsIgnoreCase(existing.getState())) {
            recordEvent(name, "CRASH", "容器状态变为 exited");
        }

        existing.setNode(node);
        existing.setImage(image);
        existing.setState(dockerState);
        existing.setStatusText(dockerState);
        existing.setDockerRestartCount(restartCount);
        existing.setCumulativeRestartCount(cumulative);
        existing.setLastContainerId(id);
        existing.setStartedAt(startedAt);
        existing.setLastPolledAt(LocalDateTime.now());
        containerStatusMapper.updateById(existing);
    }

    private void recordEvent(String containerName, String eventType, String detail) {
        ContainerEventEntity event = new ContainerEventEntity();
        event.setContainerName(containerName);
        event.setEventType(eventType);
        event.setDetail(detail);
        containerEventMapper.insert(event);
    }

    private static String stripLeadingSlash(String dockerName) {
        if (dockerName == null) {
            return null;
        }
        return dockerName.startsWith("/") ? dockerName.substring(1) : dockerName;
    }

    private static String safeShortId(String containerId) {
        if (containerId == null) {
            return "?";
        }
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
    }

    private static LocalDateTime parseDockerTimestamp(String timestamp) {
        if (timestamp == null || timestamp.startsWith(DOCKER_NEVER_STARTED_SENTINEL)) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(timestamp), ZoneId.systemDefault());
        } catch (Exception exception) {
            return null;
        }
    }
}
