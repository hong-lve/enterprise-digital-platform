package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.FlinkCheckpointHistoryEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkCheckpointHistoryMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.coordination.ClusterSingleton;
import com.company.dataops.console.service.monitoring.RealtimeMetrics;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls Flink's own GET /jobs/:id/checkpoints (FlinkStreamSubmissionClient.
 * checkpointHistory()) for every RUNNING job and persists it into
 * flink_checkpoint_history, since Flink itself only retains a bounded
 * in-memory history (default last 10) - without this, older checkpoint/
 * savepoint records would simply disappear once enough newer ones happened,
 * making both the trend view and the savepoint inventory unreliable.
 *
 * Also raises/clears checkpointFailureAlertState exactly like
 * FlinkStreamJobPollingScheduler's checkBackpressure()/checkConsumerLag() -
 * a job can stay RUNNING at the Flink-job level while its checkpoints keep
 * failing (e.g. a sink briefly unreachable), which the plain status poll
 * alone can't see.
 */
@Component
public class FlinkCheckpointHistoryScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlinkCheckpointHistoryScheduler.class);
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkCheckpointHistoryMapper flinkCheckpointHistoryMapper;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final RealtimeAlertService realtimeAlertService;
    private final String frontendUrl;
    private final RealtimeMetrics metrics;

    public FlinkCheckpointHistoryScheduler(
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkCheckpointHistoryMapper flinkCheckpointHistoryMapper,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        RealtimeAlertService realtimeAlertService,
        RealtimeMetrics metrics,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkCheckpointHistoryMapper = flinkCheckpointHistoryMapper;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
        this.realtimeAlertService = realtimeAlertService;
        this.metrics = metrics;
        this.frontendUrl = frontendUrl;
    }

    @ClusterSingleton(value = "flink-checkpoint-history", lockAtMostSeconds = 300)
    @Scheduled(fixedDelay = 30000)
    public void pollCheckpointHistory() {
        List<FlinkStreamJobEntity> runningJobs = flinkStreamJobMapper.selectList(new LambdaQueryWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getStatus, "RUNNING")
            .isNotNull(FlinkStreamJobEntity::getFlinkJobId));
        for (FlinkStreamJobEntity job : runningJobs) {
            try {
                syncHistory(job);
            } catch (Exception exception) {
                LOGGER.warn("Checkpoint history sync failed for job {} ({}): {}", job.getId(), job.getName(), exception.getMessage());
            }
        }
    }

    private void syncHistory(FlinkStreamJobEntity job) {
        List<FlinkStreamSubmissionClient.CheckpointRecord> records = flinkStreamSubmissionClient.checkpointHistory(job.getFlinkJobId());
        if (records.isEmpty()) {
            return;
        }
        for (FlinkStreamSubmissionClient.CheckpointRecord record : records) {
            upsert(job, record);
        }
        checkLatestForAlert(job, records);
    }

    private void upsert(FlinkStreamJobEntity job, FlinkStreamSubmissionClient.CheckpointRecord record) {
        FlinkCheckpointHistoryEntity existing = flinkCheckpointHistoryMapper.selectOne(new LambdaQueryWrapper<FlinkCheckpointHistoryEntity>()
            .eq(FlinkCheckpointHistoryEntity::getJobId, job.getId())
            .eq(FlinkCheckpointHistoryEntity::getFlinkJobId, job.getFlinkJobId())
            .eq(FlinkCheckpointHistoryEntity::getCheckpointId, record.checkpointId()));
        if (existing == null) {
            FlinkCheckpointHistoryEntity entity = new FlinkCheckpointHistoryEntity();
            entity.setJobId(job.getId());
            entity.setFlinkJobId(job.getFlinkJobId());
            entity.setCheckpointId(record.checkpointId());
            applyRecord(entity, record);
            entity.setDisposed(false);
            try {
                flinkCheckpointHistoryMapper.insert(entity);
                metrics.checkpoint(record.status(), record.endToEndDurationMs());
            } catch (DuplicateKeyException ignored) {
                FlinkCheckpointHistoryEntity concurrent = flinkCheckpointHistoryMapper.selectOne(new LambdaQueryWrapper<FlinkCheckpointHistoryEntity>()
                    .eq(FlinkCheckpointHistoryEntity::getJobId, job.getId())
                    .eq(FlinkCheckpointHistoryEntity::getFlinkJobId, job.getFlinkJobId())
                    .eq(FlinkCheckpointHistoryEntity::getCheckpointId, record.checkpointId()));
                if (concurrent != null) {
                    applyRecord(concurrent, record);
                    flinkCheckpointHistoryMapper.updateById(concurrent);
                }
            }
            return;
        }
        // A checkpoint's own status can still transition (IN_PROGRESS ->
        // COMPLETED/FAILED) between polls, filling in duration/size/
        // externalPath/failureMessage that weren't known yet - re-apply every
        // poll rather than only ever inserting once.
        applyRecord(existing, record);
        flinkCheckpointHistoryMapper.updateById(existing);
    }

    private void applyRecord(FlinkCheckpointHistoryEntity entity, FlinkStreamSubmissionClient.CheckpointRecord record) {
        entity.setCheckpointType(record.checkpointType());
        entity.setStatus(record.status());
        entity.setTriggerTimestamp(record.triggerTimestamp());
        entity.setLatestAckTimestamp(record.latestAckTimestamp());
        entity.setEndToEndDurationMs(record.endToEndDurationMs());
        entity.setStateSizeBytes(record.stateSizeBytes());
        entity.setExternalPath(record.externalPath());
        entity.setFailureMessage(record.failureMessage());
    }

    private void checkLatestForAlert(FlinkStreamJobEntity job, List<FlinkStreamSubmissionClient.CheckpointRecord> records) {
        FlinkStreamSubmissionClient.CheckpointRecord latest = records.stream()
            .filter(record -> record.triggerTimestamp() != null)
            .max(Comparator.comparingLong(FlinkStreamSubmissionClient.CheckpointRecord::triggerTimestamp))
            .orElse(null);
        if (latest == null) {
            return;
        }
        int tolerated = job.getTolerableFailedCheckpoints() == null ? 0 : job.getTolerableFailedCheckpoints();
        long consecutiveFailures = records.stream()
            .filter(record -> record.triggerTimestamp() != null)
            .sorted(Comparator.comparingLong(FlinkStreamSubmissionClient.CheckpointRecord::triggerTimestamp).reversed())
            .takeWhile(record -> "FAILED".equals(record.status()))
            .count();
        boolean failing = consecutiveFailures > tolerated;
        if (failing && !"ALERTING".equals(job.getCheckpointFailureAlertState())) {
            realtimeAlertService.notifyFailure(
                new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "CHECKPOINT_FAILURE"),
                job.getOwner(),
                "Flink 流作业 Checkpoint 失败：" + job.getName(),
                latest.failureMessage() == null ? "无详情" : latest.failureMessage(),
                frontendUrl + "/realtime/flink-jobs"
            );
            updateAlertState(job, "ALERTING");
        } else if ("COMPLETED".equals(latest.status()) && "ALERTING".equals(job.getCheckpointFailureAlertState())) {
            realtimeAlertService.notifyRecovery(
                new RealtimeAlertService.AlertSubject("FLINK_JOB", job.getId(), job.getName(), "CHECKPOINT_FAILURE"),
                job.getOwner(),
                "Flink 流作业 Checkpoint 已恢复：" + job.getName(),
                frontendUrl + "/realtime/flink-jobs"
            );
            updateAlertState(job, "OK");
        }
    }

    private void updateAlertState(FlinkStreamJobEntity job, String state) {
        flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getId, job.getId())
            .set(FlinkStreamJobEntity::getCheckpointFailureAlertState, state));
    }
}
