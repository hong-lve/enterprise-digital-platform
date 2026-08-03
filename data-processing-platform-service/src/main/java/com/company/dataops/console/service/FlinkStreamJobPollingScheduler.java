package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.flink.FlinkBackpressureInspector;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.kafka.KafkaConsumerLagInspector;
import com.company.dataops.console.service.recovery.RecoveryOrchestrator;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(FlinkStreamJobPollingScheduler.class);

    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final FlinkBackpressureInspector flinkBackpressureInspector;
    private final KafkaConsumerLagInspector kafkaConsumerLagInspector;
    private final RealtimeAlertService realtimeAlertService;
    private final RecoveryOrchestrator recoveryOrchestrator;
    private final String frontendUrl;
    private final double backpressureAlertThreshold;
    private final long consumerLagAlertThreshold;

    public FlinkStreamJobPollingScheduler(
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        FlinkBackpressureInspector flinkBackpressureInspector,
        KafkaConsumerLagInspector kafkaConsumerLagInspector,
        RealtimeAlertService realtimeAlertService,
        RecoveryOrchestrator recoveryOrchestrator,
        @Value("${platform.web.frontend-url}") String frontendUrl,
        @Value("${platform.bigdata.flink-backpressure-alert-threshold:0.5}") double backpressureAlertThreshold,
        @Value("${platform.bigdata.flink-consumer-lag-alert-threshold:500}") long consumerLagAlertThreshold
    ) {
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
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
        // FAILED jobs have to stay in this query too, not just RUNNING ones -
        // otherwise a job that fails drops out of every future poll forever,
        // the same bug class CdcSourceStatusScheduler's own query was fixed
        // for earlier (see that fix's commit/memory) - nothing would ever
        // attempt automatic recovery on it, and nothing would notice if it's
        // later fixed by hand outside this app.
        List<FlinkStreamJobEntity> jobs = flinkStreamJobMapper.selectList(new LambdaQueryWrapper<FlinkStreamJobEntity>()
            .in(FlinkStreamJobEntity::getStatus, "RUNNING", "FAILED")
            .isNotNull(FlinkStreamJobEntity::getFlinkJobId));
        for (FlinkStreamJobEntity job : jobs) {
            if ("FAILED".equals(job.getStatus())) {
                attemptRecovery(job);
                continue; // not healthy right now - backpressure/lag aren't meaningful questions
            }
            FlinkStreamSubmissionClient.FlinkJobStatus status = flinkStreamSubmissionClient.status(job.getFlinkJobId());
            if (!"RUNNING".equals(status.state())) {
                job.setStatus(status.state());
                job.setLastError(status.message());
                // Flink's own restart strategy already retried internally before
                // the job reached this terminal state, so by the time we see
                // FAILED here it's a real failure worth alerting on immediately -
                // no separate retry count needed at this layer.
                if ("FAILED".equals(status.state())) {
                    recoveryOrchestrator.recordFailureDetected("FLINK_JOB", job.getId(), job.getName(), job.getLastError());
                    if (!"ALERTING".equals(job.getAlertState())) {
                        realtimeAlertService.notifyFailure(
                            new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "JOB_FAILURE"),
                            job.getOwner(),
                            "Flink 流作业失败：" + job.getName(),
                            job.getLastError(),
                            frontendUrl + "/realtime/flink-jobs"
                        );
                        job.setAlertState("ALERTING");
                    }
                }
                flinkBackpressureInspector.forget(job.getFlinkJobId());
                // Targeted update, not updateById(job) - job is a snapshot from
                // this method's own selectList() at the top of this poll tick.
                // If /start (FlinkStreamJobController) resubmits this job and
                // writes a fresh flinkJobId in between that select and this
                // write, updateById() would blast every column from this stale
                // snapshot back over the row - including the now-outdated
                // flinkJobId - silently undoing the resubmission. Confirmed
                // live: this is exactly what caused the app's own job list to
                // show a dead flinkJobId for a job that was actually running
                // fine under a newer one.
                flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                    .eq(FlinkStreamJobEntity::getId, job.getId())
                    .set(FlinkStreamJobEntity::getStatus, job.getStatus())
                    .set(FlinkStreamJobEntity::getLastError, job.getLastError())
                    .set(FlinkStreamJobEntity::getAlertState, job.getAlertState()));
                continue; // not healthy right now - backpressure isn't a meaningful question
            }
            checkBackpressure(job);
            checkConsumerLag(job);
        }
    }

    /**
     * Automatic recovery for a job already confirmed FAILED - status() above
     * only ever reports FAILED for a genuinely terminal Flink state (see its
     * own javadoc's transient-vs-terminal distinction), so this isn't racing
     * a network blip. Resubmits via the same submit() the manual "启动" button
     * uses, which already resumes from job.savepointPath when one is set -
     * this is the roadmap's "自动从最新可用 Savepoint 恢复". Tiered/circuit-broken
     * via RecoveryOrchestrator exactly like CdcSourceStatusScheduler's
     * equivalent method.
     */
    private void attemptRecovery(FlinkStreamJobEntity job) {
        if (!recoveryOrchestrator.shouldAttempt("FLINK_JOB", job.getId())) {
            return;
        }
        recoveryOrchestrator.recordAttempt("FLINK_JOB", job.getId(), job.getName());
        try {
            String flinkJobId = flinkStreamSubmissionClient.submit(job);
            recoveryOrchestrator.recordRecovered("FLINK_JOB", job.getId(), job.getName());
            String newAlertState = job.getAlertState();
            if ("ALERTING".equals(job.getAlertState())) {
                realtimeAlertService.notifyRecovery(
                    new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "JOB_FAILURE"),
                    job.getOwner(),
                    "Flink 流作业已自动恢复：" + job.getName(),
                    frontendUrl + "/realtime/flink-jobs"
                );
                newAlertState = "OK";
            }
            flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getId, job.getId())
                .set(FlinkStreamJobEntity::getFlinkJobId, flinkJobId)
                .set(FlinkStreamJobEntity::getStatus, "RUNNING")
                .set(FlinkStreamJobEntity::getLastError, null)
                .set(FlinkStreamJobEntity::getAlertState, newAlertState));
        } catch (Exception exception) {
            // Still FAILED - status/lastError already reflect the original
            // failure from the poll cycle that first detected it; this
            // attempt's own outcome lives in the recovery timeline instead of
            // growing lastError with a repeated suffix every retry.
            LOGGER.warn("Auto-recovery submit failed for Flink job {} ({}): {}", job.getId(), job.getName(), exception.getMessage());
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
        // Targeted update - see pollRunningJobs()'s identical comment on why
        // updateById(job) here would risk clobbering a concurrent /start's
        // fresh flinkJobId with this method's own stale snapshot.
        flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, job.getId())
            .set(FlinkStreamJobEntity::getBackpressureRatio, job.getBackpressureRatio())
            .set(FlinkStreamJobEntity::getBackpressureAlertState, job.getBackpressureAlertState()));
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
        // Targeted update - see pollRunningJobs()'s identical comment.
        flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, job.getId())
            .set(FlinkStreamJobEntity::getConsumerLagRecords, job.getConsumerLagRecords())
            .set(FlinkStreamJobEntity::getConsumerLagAlertState, job.getConsumerLagAlertState()));
    }
}
