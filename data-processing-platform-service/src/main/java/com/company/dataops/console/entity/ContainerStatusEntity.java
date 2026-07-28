package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("container_status")
public class ContainerStatusEntity {
    private Long id;
    private String containerName;
    private String node;
    private String image;
    private String state;
    private String statusText;
    // Docker's own RestartCount (from `docker inspect`) - resets to 0
    // whenever the container is recreated, not just restarted, so it can't
    // be trusted as a durable history on its own. See cumulativeRestartCount.
    private Integer dockerRestartCount;
    // Survives container recreation - DockerMonitoringPoller increments this
    // whenever it detects dockerRestartCount going up OR the container's ID
    // changing (a recreation, which resets dockerRestartCount to 0 but is
    // still a "the service went down and came back" event worth counting).
    private Integer cumulativeRestartCount;
    // Docker's container ID (not the name) - lets the poller tell a full
    // recreation (new ID, dockerRestartCount reset to 0) apart from an
    // in-place restart (same ID, dockerRestartCount incremented).
    private String lastContainerId;
    private LocalDateTime startedAt;
    private LocalDateTime lastPolledAt;
}
