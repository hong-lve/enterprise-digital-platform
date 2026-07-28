package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.flink.FlinkBackpressureInspector;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.kafka.KafkaConsumerLagInspector;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A streaming job never reaches a terminal "done" state on its own the way a
 * batch job does, so nothing else naturally notices when one fails or gets
 * cancelled outside this app (e.g. from the Flink UI). This periodically
 * checks every job we think is RUNNING and syncs its real state back.
 *
 * Also checks backpressure (see FlinkBackpressureInspector) and, for jobs
 * that declare a Kafka consumer group, real Kafka consumer lag (see
 * KafkaConsumerLagInspector) for jobs still RUNNING at the Flink level - a
 * job can stay RUNNING while falling behind its upstream source, which the
 * status check alone can't catch. These are separate failure dimensions
 * from alert_state (job FAILED/RUNNING) and from each other, each tracked
 * via its own *_alert_state column so none of the three can mask another -
 * same pattern as CdcSourceStatusScheduler's lag_alert_state.
 */
@Component
public class FlinkStreamJobPollingScheduler {
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final FlinkBackpressureInspector flinkBackpressureInspector;
    private final KafkaConsumerLagInspector kafkaConsumerLagInspector;
    private final RealtimeAlertService realtimeAlertService;
    private final String frontendUrl;
    private final double backpressureAlertThreshold;
    private final long consumerLagAlertThreshold;

    public FlinkStreamJobPollingScheduler(
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        FlinkBackpressureInspector flinkBackpressureInspector,
        KafkaConsumerLagInspector kafkaConsumerLagInspector,
        RealtimeAlertService realtimeAlertService,
        @Value("${platform.web.frontend-url}") String frontendUrl,
        @Value("${platform.bigdata.flink-backpressure-alert-threshold:0.5}") double backpressureAlertThreshold,
        @Value("${platform.bigdata.flink-consumer-lag-alert-threshold:500}") long consumerLagAlertThreshold
    ) {
        this.flinkStreamJobMapper = flinkStreamJobMapper;
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
        List<FlinkStreamJobEntity> runningJobs = flinkStreamJobMapper.selectList(new LambdaQueryWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getStatus, "RUNNING")
            .isNotNull(FlinkStreamJobEntity::getFlinkJobId));
        for (FlinkStreamJobEntity job : runningJobs) {
            FlinkStreamSubmissionClient.FlinkJobStatus status = flinkStreamSubmissionClient.status(job.getFlinkJobId());
            if (!"RUNNING".equals(status.state())) {
                job.setStatus(status.state());
                job.setLastError(status.message());
                // Flink's own restart strategy already retried internally before
                // the job reached this terminal state, so by the time we see
                // FAILED here it's a real failure worth alerting on immediately -
                // no separate retry count needed at this layer.
                if ("FAILED".equals(status.state()) && !"ALERTING".equals(job.getAlertState())) {
                    realtimeAlertService.notifyFailure(
                        new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "JOB_FAILURE"),
                        job.getOwner(),
                        "Flink 流作业失败：" + job.getName(),
                        job.getLastError(),
                        frontendUrl + "/realtime/flink-jobs"
                    );
                    job.setAlertState("ALERTING");
                }
                flinkBackpressureInspector.forget(job.getFlinkJobId());
                flinkStreamJobMapper.updateById(job);
                continue; // not healthy right now - backpressure isn't a meaningful question
            }
            checkBackpressure(job);
            checkConsumerLag(job);
        }
    }

    private void checkBackpressure(FlinkStreamJobEntity job) {
        Double ratio = flinkBackpressureInspector.currentRatio(job.getFlinkJobId());
        job.setBackpressureRatio(ratio);

        boolean backpressured = ratio != null && ratio > backpressureAlertThreshold;
        if (backpressured && !"ALERTING".equals(job.getBackpressureAlertState())) {
            realtimeAlertService.notifyFailure(
                new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "BACKPRESSURE"),
                job.getOwner(),
                "Flink 流作业反压过高：" + job.getName(),
                String.format("最近一轮反压比例 %.0f%%", ratio * 100),
                frontendUrl + "/realtime/flink-jobs"
            );
            job.setBackpressureAlertState("ALERTING");
        } else if (!backpressured && "ALERTING".equals(job.getBackpressureAlertState())) {
            realtimeAlertService.notifyRecovery(
                new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "BACKPRESSURE"),
                job.getOwner(),
                "Flink 流作业反压已恢复：" + job.getName(),
                frontendUrl + "/realtime/flink-jobs"
            );
            job.setBackpressureAlertState("OK");
        }
        flinkStreamJobMapper.updateById(job);
    }

    private void checkConsumerLag(FlinkStreamJobEntity job) {
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
                new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "CONSUMER_LAG"),
                job.getOwner(),
                "Flink 流作业消费延迟过高：" + job.getName(),
                "积压 " + lag + " 条消息未消费",
                frontendUrl + "/realtime/flink-jobs"
            );
            job.setConsumerLagAlertState("ALERTING");
        } else if (!lagging && "ALERTING".equals(job.getConsumerLagAlertState())) {
            realtimeAlertService.notifyRecovery(
                new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "CONSUMER_LAG"),
                job.getOwner(),
                "Flink 流作业消费延迟已恢复：" + job.getName(),
                frontendUrl + "/realtime/flink-jobs"
            );
            job.setConsumerLagAlertState("OK");
        }
        flinkStreamJobMapper.updateById(job);
    }
}
