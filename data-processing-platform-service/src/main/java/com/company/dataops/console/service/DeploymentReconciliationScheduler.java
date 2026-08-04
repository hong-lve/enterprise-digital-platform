package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.coordination.ClusterSingleton;
import com.company.dataops.console.service.coordination.JobOperationCoordinator;
import com.company.dataops.console.service.flink.FlinkSqlJobSubmissionService;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeploymentReconciliationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeploymentReconciliationScheduler.class);
    private static final List<String> INCOMPLETE_STATES = List.of("PREPARING", "STOPPING", "DEPLOYING", "VERIFYING");

    private final FlinkStreamJobMapper streamJobMapper;
    private final FlinkSqlJobMapper sqlJobMapper;
    private final FlinkStreamSubmissionClient streamSubmissionClient;
    private final FlinkSqlJobSubmissionService sqlSubmissionService;
    private final JobOperationCoordinator operationCoordinator;

    public DeploymentReconciliationScheduler(
        FlinkStreamJobMapper streamJobMapper,
        FlinkSqlJobMapper sqlJobMapper,
        FlinkStreamSubmissionClient streamSubmissionClient,
        FlinkSqlJobSubmissionService sqlSubmissionService,
        JobOperationCoordinator operationCoordinator
    ) {
        this.streamJobMapper = streamJobMapper;
        this.sqlJobMapper = sqlJobMapper;
        this.streamSubmissionClient = streamSubmissionClient;
        this.sqlSubmissionService = sqlSubmissionService;
        this.operationCoordinator = operationCoordinator;
    }

    @ClusterSingleton(value = "deployment-reconciliation", lockAtMostSeconds = 600)
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void reconcile() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(2);
        streamJobMapper.selectList(new LambdaQueryWrapper<FlinkStreamJobEntity>()
            .in(FlinkStreamJobEntity::getDeploymentStatus, INCOMPLETE_STATES)
            .lt(FlinkStreamJobEntity::getDeploymentUpdatedAt, staleBefore))
            .forEach(this::reconcileStreamSafely);
        sqlJobMapper.selectList(new LambdaQueryWrapper<FlinkSqlJobEntity>()
            .in(FlinkSqlJobEntity::getDeploymentStatus, INCOMPLETE_STATES)
            .lt(FlinkSqlJobEntity::getDeploymentUpdatedAt, staleBefore))
            .forEach(this::reconcileSqlSafely);
    }

    private void reconcileStreamSafely(FlinkStreamJobEntity job) {
        try {
            operationCoordinator.execute("FLINK_STREAM_JOB", job.getId(), "RECONCILE", null, Duration.ofMinutes(5),
                () -> reconcileStream(job));
        } catch (Exception exception) {
            LOGGER.warn("Could not reconcile stream deployment {}: {}", job.getId(), exception.getMessage());
        }
    }

    private void reconcileSqlSafely(FlinkSqlJobEntity job) {
        try {
            operationCoordinator.execute("FLINK_SQL_JOB", job.getId(), "RECONCILE", null, Duration.ofMinutes(5),
                () -> reconcileSql(job));
        } catch (Exception exception) {
            LOGGER.warn("Could not reconcile SQL deployment {}: {}", job.getId(), exception.getMessage());
        }
    }

    private void reconcileStream(FlinkStreamJobEntity job) {
        if ("DEPLOYING".equals(job.getDeploymentStatus())) {
            job.setSavepointPath(job.getPendingResumePath());
            try {
                String flinkJobId = streamSubmissionClient.submit(job);
                markStreamVerifying(job.getId(), flinkJobId, "补偿协调器已重新提交作业，等待 Flink 确认");
            } catch (Exception exception) {
                markStreamFailed(job.getId(), "补偿提交失败：" + exception.getMessage());
            }
            return;
        }
        if ("VERIFYING".equals(job.getDeploymentStatus())) {
            FlinkStreamSubmissionClient.FlinkJobStatus status = streamSubmissionClient.status(job.getFlinkJobId());
            if ("运行中：RUNNING".equals(status.message())) {
                markStreamRunning(job.getId());
            } else if (!"RUNNING".equals(status.state())) {
                markStreamFailed(job.getId(), "部署验证失败：" + status.message());
            }
            return;
        }
        compensateInterruptedStop(job.getId(), job.getFlinkJobId(), true);
    }

    private void reconcileSql(FlinkSqlJobEntity job) {
        if ("DEPLOYING".equals(job.getDeploymentStatus())) {
            job.setSavepointPath(job.getPendingResumePath());
            try {
                String flinkJobId = sqlSubmissionService.submit(job);
                sqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                    .eq(FlinkSqlJobEntity::getId, job.getId())
                    .set(FlinkSqlJobEntity::getFlinkJobId, flinkJobId)
                    .set(FlinkSqlJobEntity::getStatus, "STARTING")
                    .set(FlinkSqlJobEntity::getDeploymentStatus, "VERIFYING")
                    .set(FlinkSqlJobEntity::getDeploymentMessage, "补偿协调器已重新提交作业，等待 Flink 确认")
                    .set(FlinkSqlJobEntity::getDeploymentUpdatedAt, LocalDateTime.now()));
            } catch (Exception exception) {
                markSqlFailed(job.getId(), "补偿提交失败：" + exception.getMessage());
            }
            return;
        }
        if ("VERIFYING".equals(job.getDeploymentStatus())) {
            FlinkStreamSubmissionClient.FlinkJobStatus status = streamSubmissionClient.status(job.getFlinkJobId());
            if ("运行中：RUNNING".equals(status.message())) {
                sqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                    .eq(FlinkSqlJobEntity::getId, job.getId())
                    .set(FlinkSqlJobEntity::getStatus, "RUNNING")
                    .set(FlinkSqlJobEntity::getDeploymentStatus, "RUNNING")
                    .set(FlinkSqlJobEntity::getDeploymentMessage, null)
                    .set(FlinkSqlJobEntity::getPendingResumePath, null)
                    .set(FlinkSqlJobEntity::getDeploymentUpdatedAt, LocalDateTime.now()));
            } else if (!"RUNNING".equals(status.state())) {
                markSqlFailed(job.getId(), "部署验证失败：" + status.message());
            }
            return;
        }
        compensateInterruptedStop(job.getId(), job.getFlinkJobId(), false);
    }

    private void compensateInterruptedStop(Long id, String flinkJobId, boolean streamJob) {
        boolean oldInstanceStillRunning = flinkJobId != null && streamSubmissionClient.isRunning(flinkJobId);
        String message = oldInstanceStillRunning
            ? "部署操作中断，旧实例仍在运行，已保留旧实例并转人工确认"
            : "部署操作中断且旧实例已停止，缺少可安全续跑的阶段信息，已转人工恢复";
        if (streamJob) {
            streamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getId, id)
                .set(FlinkStreamJobEntity::getStatus, oldInstanceStillRunning ? "RUNNING" : "FAILED")
                .set(FlinkStreamJobEntity::getDeploymentStatus, "ROLLBACK")
                .set(FlinkStreamJobEntity::getDeploymentMessage, message)
                .set(FlinkStreamJobEntity::getDeploymentUpdatedAt, LocalDateTime.now()));
        } else {
            sqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                .eq(FlinkSqlJobEntity::getId, id)
                .set(FlinkSqlJobEntity::getStatus, oldInstanceStillRunning ? "RUNNING" : "FAILED")
                .set(FlinkSqlJobEntity::getDeploymentStatus, "ROLLBACK")
                .set(FlinkSqlJobEntity::getDeploymentMessage, message)
                .set(FlinkSqlJobEntity::getDeploymentUpdatedAt, LocalDateTime.now()));
        }
    }

    private void markStreamVerifying(Long id, String flinkJobId, String message) {
        streamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, id)
            .set(FlinkStreamJobEntity::getFlinkJobId, flinkJobId)
            .set(FlinkStreamJobEntity::getStatus, "STARTING")
            .set(FlinkStreamJobEntity::getDeploymentStatus, "VERIFYING")
            .set(FlinkStreamJobEntity::getDeploymentMessage, message)
            .set(FlinkStreamJobEntity::getDeploymentUpdatedAt, LocalDateTime.now()));
    }

    private void markStreamRunning(Long id) {
        streamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, id)
            .set(FlinkStreamJobEntity::getStatus, "RUNNING")
            .set(FlinkStreamJobEntity::getDeploymentStatus, "RUNNING")
            .set(FlinkStreamJobEntity::getDeploymentMessage, null)
            .set(FlinkStreamJobEntity::getPendingResumePath, null)
            .set(FlinkStreamJobEntity::getDeploymentUpdatedAt, LocalDateTime.now()));
    }

    private void markStreamFailed(Long id, String message) {
        streamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, id)
            .set(FlinkStreamJobEntity::getStatus, "FAILED")
            .set(FlinkStreamJobEntity::getDeploymentStatus, "ROLLBACK")
            .set(FlinkStreamJobEntity::getDeploymentMessage, message)
            .set(FlinkStreamJobEntity::getLastError, message)
            .set(FlinkStreamJobEntity::getDeploymentUpdatedAt, LocalDateTime.now()));
    }

    private void markSqlFailed(Long id, String message) {
        sqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, id)
            .set(FlinkSqlJobEntity::getStatus, "FAILED")
            .set(FlinkSqlJobEntity::getDeploymentStatus, "ROLLBACK")
            .set(FlinkSqlJobEntity::getDeploymentMessage, message)
            .set(FlinkSqlJobEntity::getLastError, message)
            .set(FlinkSqlJobEntity::getDeploymentUpdatedAt, LocalDateTime.now()));
    }
}
