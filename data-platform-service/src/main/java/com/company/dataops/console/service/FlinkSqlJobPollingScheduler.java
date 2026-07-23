package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.service.flink.FlinkBackpressureInspector;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.kafka.KafkaConsumerLagInspector;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mirrors FlinkStreamJobPollingScheduler exactly, operating on
 * FlinkSqlJobEntity instead - same reasoning applies unchanged: a SQL job's
 * underlying Flink job is submitted the same way (just via SQL Gateway
 * instead of jar upload), so it fails/restarts through Flink's own
 * mechanisms the same way, and reuses the same FlinkBackpressureInspector/
 * KafkaConsumerLagInspector/RealtimeAlertService/FlinkStreamSubmissionClient
 * instances - those are job-id/vertex-id keyed, not tied to how the job was
 * submitted. Only RUNNING jobs are polled (not FAILED, unlike
 * CdcSourceStatusScheduler's fix) - Flink's own restart strategy already
 * exhausts its retries before a job's REST-reported state goes terminal.
 */
@Component
public class FlinkSqlJobPollingScheduler {
    private final FlinkSqlJobMapper flinkSqlJobMapper;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final FlinkBackpressureInspector flinkBackpressureInspector;
    private final KafkaConsumerLagInspector kafkaConsumerLagInspector;
    private final RealtimeAlertService realtimeAlertService;
    private final String frontendUrl;
    private final double backpressureAlertThreshold;
    private final long consumerLagAlertThreshold;

    public FlinkSqlJobPollingScheduler(
        FlinkSqlJobMapper flinkSqlJobMapper,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        FlinkBackpressureInspector flinkBackpressureInspector,
        KafkaConsumerLagInspector kafkaConsumerLagInspector,
        RealtimeAlertService realtimeAlertService,
        @Value("${platform.web.frontend-url}") String frontendUrl,
        @Value("${platform.bigdata.flink-backpressure-alert-threshold:0.5}") double backpressureAlertThreshold,
        @Value("${platform.bigdata.flink-consumer-lag-alert-threshold:500}") long consumerLagAlertThreshold
    ) {
        this.flinkSqlJobMapper = flinkSqlJobMapper;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
        this.flinkBackpressureInspector = flinkBackpressureInspector;
        this.kafkaConsumerLagInspector = kafkaConsumerLagInspector;
        this.realtimeAlertService = realtimeAlertService;
        this.frontendUrl = frontendUrl;
        this.backpressureAlertThreshold = backpressureAlertThreshold;
        this.consumerLagAlertThreshold = consumerLagAlertThreshold;
    }

    @Scheduled(fixedDelay = 15000)
    public void pollRunningJobs() {
        List<FlinkSqlJobEntity> runningJobs = flinkSqlJobMapper.selectList(new LambdaQueryWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getStatus, "RUNNING")
            .isNotNull(FlinkSqlJobEntity::getFlinkJobId));
        for (FlinkSqlJobEntity job : runningJobs) {
            FlinkStreamSubmissionClient.FlinkJobStatus status = flinkStreamSubmissionClient.status(job.getFlinkJobId());
            if (!"RUNNING".equals(status.state())) {
                job.setStatus(status.state());
                job.setLastError(status.message());
                if ("FAILED".equals(status.state()) && !"ALERTING".equals(job.getAlertState())) {
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
                flinkSqlJobMapper.updateById(job);
                continue; // not healthy right now - backpressure isn't a meaningful question
            }
            checkBackpressure(job);
            checkConsumerLag(job);
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
        flinkSqlJobMapper.updateById(job);
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
        flinkSqlJobMapper.updateById(job);
    }
}
