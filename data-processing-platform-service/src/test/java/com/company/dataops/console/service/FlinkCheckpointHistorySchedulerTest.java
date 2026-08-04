package com.company.dataops.console.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.company.dataops.console.entity.FlinkCheckpointHistoryEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkCheckpointHistoryMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.monitoring.RealtimeMetrics;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FlinkCheckpointHistorySchedulerTest {
    @BeforeAll
    static void initMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(FlinkStreamJobMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, FlinkStreamJobEntity.class);
        TableInfoHelper.initTableInfo(assistant, FlinkCheckpointHistoryEntity.class);
    }

    @Test
    void inProgressCheckpointDoesNotClearFailureAlert() {
        FlinkStreamJobMapper jobMapper = mock(FlinkStreamJobMapper.class);
        FlinkCheckpointHistoryMapper historyMapper = mock(FlinkCheckpointHistoryMapper.class);
        FlinkStreamSubmissionClient client = mock(FlinkStreamSubmissionClient.class);
        RealtimeAlertService alerts = mock(RealtimeAlertService.class);
        FlinkStreamJobEntity job = new FlinkStreamJobEntity();
        job.setId(1L);
        job.setName("orders");
        job.setFlinkJobId("job-1");
        job.setStatus("RUNNING");
        job.setCheckpointFailureAlertState("ALERTING");
        job.setTolerableFailedCheckpoints(0);
        when(jobMapper.selectList(any())).thenReturn(List.of(job));
        when(historyMapper.selectOne(any())).thenReturn(new FlinkCheckpointHistoryEntity());
        when(client.checkpointHistory("job-1")).thenReturn(List.of(
            record(2L, "IN_PROGRESS", 200L), record(1L, "FAILED", 100L)));

        new FlinkCheckpointHistoryScheduler(jobMapper, historyMapper, client, alerts, mock(RealtimeMetrics.class), "http://frontend")
            .pollCheckpointHistory();

        verify(alerts, never()).notifyRecovery(any(), any(), any(), any());
    }

    private FlinkStreamSubmissionClient.CheckpointRecord record(Long id, String status, Long timestamp) {
        return new FlinkStreamSubmissionClient.CheckpointRecord(id, status, "CHECKPOINT", timestamp,
            null, null, null, null, "FAILED".equals(status) ? "failed" : null);
    }
}
