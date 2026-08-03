package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.FlinkCheckpointHistoryEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkCheckpointHistoryMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flink never auto-deletes savepoints (unlike regular checkpoints, which
 * state.checkpoints.num-retained already subsumes automatically) - they're
 * meant to be kept until a human or a policy says otherwise. Without this,
 * every stop-with-savepoint (manual stop, or a future auto-recovery flow)
 * accumulates state files on the checkpoint volume forever. Runs hourly, not
 * on FlinkCheckpointHistoryScheduler's 30s cadence - savepoint counts change
 * slowly, and disposal is a real Flink REST operation worth rate-limiting.
 */
@Component
public class FlinkSavepointRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlinkSavepointRetentionScheduler.class);

    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkCheckpointHistoryMapper flinkCheckpointHistoryMapper;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;

    public FlinkSavepointRetentionScheduler(
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkCheckpointHistoryMapper flinkCheckpointHistoryMapper,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient
    ) {
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkCheckpointHistoryMapper = flinkCheckpointHistoryMapper;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void enforceRetention() {
        List<FlinkStreamJobEntity> jobs = flinkStreamJobMapper.selectList(new LambdaQueryWrapper<FlinkStreamJobEntity>()
            .isNotNull(FlinkStreamJobEntity::getSavepointRetentionCount)
            .gt(FlinkStreamJobEntity::getSavepointRetentionCount, 0));
        for (FlinkStreamJobEntity job : jobs) {
            enforceForJob(job);
        }
    }

    private void enforceForJob(FlinkStreamJobEntity job) {
        List<FlinkCheckpointHistoryEntity> savepoints = flinkCheckpointHistoryMapper.selectList(new LambdaQueryWrapper<FlinkCheckpointHistoryEntity>()
            .eq(FlinkCheckpointHistoryEntity::getJobId, job.getId())
            .in(FlinkCheckpointHistoryEntity::getCheckpointType, "SAVEPOINT", "SYNC_SAVEPOINT")
            .eq(FlinkCheckpointHistoryEntity::getDisposed, false)
            .isNotNull(FlinkCheckpointHistoryEntity::getExternalPath)
            .orderByDesc(FlinkCheckpointHistoryEntity::getTriggerTimestamp));
        if (savepoints.size() <= job.getSavepointRetentionCount()) {
            return;
        }
        List<FlinkCheckpointHistoryEntity> toDispose = savepoints.subList(job.getSavepointRetentionCount(), savepoints.size());
        for (FlinkCheckpointHistoryEntity entity : toDispose) {
            // Never dispose the savepoint a job would actually resume from on
            // its next start() - even if it falls outside the retention
            // window (e.g. a job paused a long time with only one savepoint
            // on record) - disposing it would silently turn the next resume
            // into starting over from nothing.
            if (entity.getExternalPath().equals(job.getSavepointPath())) {
                continue;
            }
            try {
                flinkStreamSubmissionClient.disposeSavepoint(entity.getExternalPath());
                entity.setDisposed(true);
                flinkCheckpointHistoryMapper.updateById(entity);
            } catch (Exception exception) {
                LOGGER.warn("Failed to dispose savepoint {} for job {}: {}", entity.getExternalPath(), job.getId(), exception.getMessage());
            }
        }
    }
}
