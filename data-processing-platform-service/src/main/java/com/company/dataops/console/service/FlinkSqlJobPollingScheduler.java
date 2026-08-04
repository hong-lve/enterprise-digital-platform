package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.service.flink.FlinkBackpressureInspector;
import com.company.dataops.console.service.flink.FlinkSqlJobSubmissionService;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.kafka.KafkaConsumerLagInspector;
import com.company.dataops.console.service.recovery.RecoveryOrchestrator;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors FlinkStreamJobPollingScheduler exactly, operating on
 * FlinkSqlJobEntity instead - same reasoning applies unchanged: a SQL job's
 * underlying Flink job is submitted the same way (just via SQL Gateway
 * instead of jar upload), so it fails/restarts through Flink's own
 * mechanisms the same way, and reuses the same FlinkBackpressureInspector/
 * KafkaConsumerLagInspector/RealtimeAlertService/FlinkStreamSubmissionClient
 * instances - those are job-id/vertex-id keyed, not tied to how the job was
 * submitted. RUNNING, STARTING and FAILED jobs are polled so terminal SQL
 * jobs use the same leased recovery orchestration as jar jobs.
 */
@Component
public class FlinkSqlJobPollingScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlinkSqlJobPollingScheduler.class);
    private final FlinkSqlJobMapper flinkSqlJobMapper;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final FlinkSqlJobSubmissionService flinkSqlJobSubmissionService;
    private final FlinkBackpressureInspector flinkBackpressureInspector;
    private final KafkaConsumerLagInspector kafkaConsumerLagInspector;
    private final RealtimeAlertService realtimeAlertService;
    private final RecoveryOrchestrator recoveryOrchestrator;
    private final String frontendUrl;
    private final double backpressureAlertThreshold;
    private final long consumerLagAlertThreshold;

    public FlinkSqlJobPollingScheduler(
        FlinkSqlJobMapper flinkSqlJobMapper,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        FlinkSqlJobSubmissionService flinkSqlJobSubmissionService,
        FlinkBackpressureInspector flinkBackpressureInspector,
        KafkaConsumerLagInspector kafkaConsumerLagInspector,
        RealtimeAlertService realtimeAlertService,
        RecoveryOrchestrator recoveryOrchestrator,
        @Value("${platform.web.frontend-url}") String frontendUrl,
        @Value("${platform.bigdata.flink-backpressure-alert-threshold:0.5}") double backpressureAlertThreshold,
        @Value("${platform.bigdata.flink-consumer-lag-alert-threshold:500}") long consumerLagAlertThreshold
    ) {
        this.flinkSqlJobMapper = flinkSqlJobMapper;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
        this.flinkSqlJobSubmissionService = flinkSqlJobSubmissionService;
        this.flinkBackpressureInspector = flinkBackpressureInspector;
        this.kafkaConsumerLagInspector = kafkaConsumerLagInspector;
        this.realtimeAlertService = realtimeAlertService;
        this.recoveryOrchestrator = recoveryOrchestrator;
        this.frontendUrl = frontendUrl;
        this.backpressureAlertThreshold = backpressureAlertThreshold;
        this.consumerLagAlertThreshold = consumerLagAlertThreshold;
    }

    @Scheduled(fixedDelay = 15000)
    public void pollRunningJobs() {
        List<FlinkSqlJobEntity> runningJobs = flinkSqlJobMapper.selectList(new LambdaQueryWrapper<FlinkSqlJobEntity>()
            .in(FlinkSqlJobEntity::getStatus, "RUNNING", "STARTING", "FAILED")
            .isNotNull(FlinkSqlJobEntity::getFlinkJobId));
        for (FlinkSqlJobEntity job : runningJobs) {
            if ("FAILED".equals(job.getStatus())) {
                attemptRecovery(job);
                continue;
            }
            FlinkStreamSubmissionClient.FlinkJobStatus status = flinkStreamSubmissionClient.status(job.getFlinkJobId());
            if (!"RUNNING".equals(status.state())) {
                job.setStatus(status.state());
                job.setLastError(status.message());
                if ("FAILED".equals(status.state()) && !"ALERTING".equals(job.getAlertState())) {
                    recoveryOrchestrator.recordFailureDetected("SQL_JOB", job.getId(), job.getName(), job.getLastError());
                    realtimeAlertService.notifyFailure(
                        new RealtimeAlertService.AlertSubject("SQL_JOB", job.getId(), job.getName(), "JOB_FAILURE"),
                        job.getOwner(),
                        "SQL 流作业失败：" + job.getName(),
                        job.getLastError(),
                        frontendUrl + "/realtime/sql-jobs"
                    );
                    job.setAlertState("ALERTING");
                }
                flinkBackpressureInspector.forget(job.getFlinkJobId());
                // Targeted update, not updateById(job) - see
                // FlinkStreamJobPollingScheduler's identical comment: job is a
                // stale snapshot from this tick's own selectList(), and
                // updateById() would blast every column (including a
                // now-outdated flinkJobId) over whatever /start just wrote.
                flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                    .eq(FlinkSqlJobEntity::getId, job.getId())
                    .set(FlinkSqlJobEntity::getStatus, job.getStatus())
                    .set(FlinkSqlJobEntity::getLastError, job.getLastError())
                    .set(FlinkSqlJobEntity::getAlertState, job.getAlertState()));
                continue; // not healthy right now - backpressure isn't a meaningful question
            }
            if (!"RUNNING".equals(job.getStatus())) {
                if (!"运行中：RUNNING".equals(status.message())) {
                    continue;
                }
                recoveryOrchestrator.recordRecovered("SQL_JOB", job.getId(), job.getName());
                if ("ALERTING".equals(job.getAlertState())) {
                    realtimeAlertService.notifyRecovery(
                        new RealtimeAlertService.AlertSubject("SQL_JOB", job.getId(), job.getName(), "JOB_FAILURE"),
                        job.getOwner(), "SQL 流作业已自动恢复：" + job.getName(), frontendUrl + "/realtime/sql-jobs");
                }
                flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                    .eq(FlinkSqlJobEntity::getId, job.getId())
                    .eq(FlinkSqlJobEntity::getFlinkJobId, job.getFlinkJobId())
                    .set(FlinkSqlJobEntity::getStatus, "RUNNING")
                    .set(FlinkSqlJobEntity::getDeploymentStatus, "RUNNING")
                    .set(FlinkSqlJobEntity::getDeploymentMessage, null)
                    .set(FlinkSqlJobEntity::getLastError, null)
                    .set(FlinkSqlJobEntity::getAlertState, "OK"));
                job.setStatus("RUNNING");
                job.setAlertState("OK");
            }
            checkBackpressure(job);
            checkConsumerLag(job);
        }
    }

    private void attemptRecovery(FlinkSqlJobEntity job) {
        if (Boolean.TRUE.equals(job.getSchemaBlocked())) {
            return;
        }
        if (!recoveryOrchestrator.tryAcquire("SQL_JOB", job.getId(), job.getName())) {
            return;
        }
        try {
            String flinkJobId = flinkSqlJobSubmissionService.submit(job);
            flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                .eq(FlinkSqlJobEntity::getId, job.getId())
                .set(FlinkSqlJobEntity::getFlinkJobId, flinkJobId)
                .set(FlinkSqlJobEntity::getStatus, "STARTING"));
        } catch (Exception exception) {
            recoveryOrchestrator.releaseLease("SQL_JOB", job.getId());
            LOGGER.warn("Auto-recovery submit failed for SQL job {} ({}): {}", job.getId(), job.getName(), exception.getMessage());
        }
    }

    private void checkBackpressure(FlinkSqlJobEntity job) {
        Double ratio = flinkBackpressureInspector.currentRatio(job.getFlinkJobId());
        job.setBackpressureRatio(ratio);

        boolean backpressured = ratio != null && ratio > backpressureAlertThreshold;
        if (backpressured && !"ALERTING".equals(job.getBackpressureAlertState())) {
            realtimeAlertService.notifyFailure(
                new RealtimeAlertService.AlertSubject("SQL_JOB", job.getId(), job.getName(), "BACKPRESSURE"),
                job.getOwner(),
                "SQL 流作业反压过高：" + job.getName(),
                String.format("最近一轮反压比例 %.0f%%", ratio * 100),
                frontendUrl + "/realtime/sql-jobs"
            );
            job.setBackpressureAlertState("ALERTING");
        } else if (!backpressured && "ALERTING".equals(job.getBackpressureAlertState())) {
            realtimeAlertService.notifyRecovery(
                new RealtimeAlertService.AlertSubject("SQL_JOB", job.getId(), job.getName(), "BACKPRESSURE"),
                job.getOwner(),
                "SQL 流作业反压已恢复：" + job.getName(),
                frontendUrl + "/realtime/sql-jobs"
            );
            job.setBackpressureAlertState("OK");
        }
        // Targeted update - see pollRunningJobs()'s identical comment.
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, job.getId())
            .set(FlinkSqlJobEntity::getBackpressureRatio, job.getBackpressureRatio())
            .set(FlinkSqlJobEntity::getBackpressureAlertState, job.getBackpressureAlertState()));
    }

    private void checkConsumerLag(FlinkSqlJobEntity job) {
        if (job.getKafkaConsumerGroupId() == null || job.getKafkaConsumerGroupId().isBlank()
            || job.getKafkaTopics() == null || job.getKafkaTopics().isBlank()) {
            return; // job doesn't declare a Kafka consumer group - not every job consumes Kafka
        }
        List<String> topics = Arrays.stream(job.getKafkaTopics().split(",")).map(String::trim).toList();
        Long lag = kafkaConsumerLagInspector.totalLag(job.getKafkaConsumerGroupId(), topics);
        job.setConsumerLagRecords(lag);

        boolean lagging = lag != null && lag > consumerLagAlertThreshold;
        if (lagging && !"ALERTING".equals(job.getConsumerLagAlertState())) {
            realtimeAlertService.notifyFailure(
                new RealtimeAlertService.AlertSubject("SQL_JOB", job.getId(), job.getName(), "CONSUMER_LAG"),
                job.getOwner(),
                "SQL 流作业消费延迟过高：" + job.getName(),
                "积压 " + lag + " 条消息未消费",
                frontendUrl + "/realtime/sql-jobs"
            );
            job.setConsumerLagAlertState("ALERTING");
        } else if (!lagging && "ALERTING".equals(job.getConsumerLagAlertState())) {
            realtimeAlertService.notifyRecovery(
                new RealtimeAlertService.AlertSubject("SQL_JOB", job.getId(), job.getName(), "CONSUMER_LAG"),
                job.getOwner(),
                "SQL 流作业消费延迟已恢复：" + job.getName(),
                frontendUrl + "/realtime/sql-jobs"
            );
            job.setConsumerLagAlertState("OK");
        }
        // Targeted update - see pollRunningJobs()'s identical comment.
        flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getId, job.getId())
            .set(FlinkSqlJobEntity::getConsumerLagRecords, job.getConsumerLagRecords())
            .set(FlinkSqlJobEntity::getConsumerLagAlertState, job.getConsumerLagAlertState()));
    }
}
