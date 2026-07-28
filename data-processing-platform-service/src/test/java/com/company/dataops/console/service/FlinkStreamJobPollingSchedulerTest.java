package com.company.dataops.console.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.flink.FlinkBackpressureInspector;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import com.company.dataops.console.service.kafka.KafkaConsumerLagInspector;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Regression test for a lost-update race: pollRunningJobs() loads its own
 * RUNNING-jobs snapshot at the top of each 15s tick, then, for jobs no
 * longer RUNNING at Flink, writes status/lastError/alertState back. If a
 * concurrent /start resubmits the same job and writes a fresh flinkJobId in
 * between this method's SELECT and its write, a naive updateById(job) would
 * blast the entire stale snapshot back over the row - including the
 * now-outdated flinkJobId - silently undoing the resubmission. Confirmed
 * live against job id 4 ("[JAR] Oracle CDC -> ClickHouse"): the row kept a
 * dead flinkJobId while a real, untracked Flink job did the actual work.
 */
class FlinkStreamJobPollingSchedulerTest {
    private FlinkStreamJobMapper flinkStreamJobMapper;
    private FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private FlinkStreamJobPollingScheduler scheduler;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        // LambdaUpdateWrapper resolves column names via MyBatis-Plus's
        // per-entity "lambda cache", normally populated when Spring wires up
        // the mapper's SqlSessionFactory. This test has no Spring context, so
        // it has to seed that cache itself before exercising the wrapper.
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        assistant.setCurrentNamespace(FlinkStreamJobMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, FlinkStreamJobEntity.class);
    }

    @BeforeEach
    void setUp() {
        flinkStreamJobMapper = mock(FlinkStreamJobMapper.class);
        flinkStreamSubmissionClient = mock(FlinkStreamSubmissionClient.class);
        FlinkBackpressureInspector flinkBackpressureInspector = mock(FlinkBackpressureInspector.class);
        KafkaConsumerLagInspector kafkaConsumerLagInspector = mock(KafkaConsumerLagInspector.class);
        RealtimeAlertService realtimeAlertService = mock(RealtimeAlertService.class);

        scheduler = new FlinkStreamJobPollingScheduler(
            flinkStreamJobMapper, flinkStreamSubmissionClient, flinkBackpressureInspector,
            kafkaConsumerLagInspector, realtimeAlertService, "http://frontend", 0.5, 500L);
    }

    @Test
    void doesNotOverwriteFlinkJobIdWhenSyncingATerminatedJobsStatus() {
        FlinkStreamJobEntity staleSnapshot = new FlinkStreamJobEntity();
        staleSnapshot.setId(4L);
        staleSnapshot.setFlinkJobId("dead-job-id-from-yesterday");
        staleSnapshot.setStatus("RUNNING");
        staleSnapshot.setAlertState("OK");

        when(flinkStreamJobMapper.selectList(any())).thenReturn(List.of(staleSnapshot));
        when(flinkStreamSubmissionClient.status("dead-job-id-from-yesterday"))
            .thenReturn(new FlinkStreamSubmissionClient.FlinkJobStatus("FAILED", "作业失败"));

        scheduler.pollRunningJobs();

        // The old bug: this method wrote the whole in-memory snapshot back
        // via updateById(job), including flinkJobId - clobbering whatever a
        // concurrent /start had just written to that column.
        verify(flinkStreamJobMapper, never()).updateById(any(FlinkStreamJobEntity.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<FlinkStreamJobEntity>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(flinkStreamJobMapper).update(isNull(), captor.capture());

        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("status"), "expected the poller to still update status: " + sqlSet);
        assertFalse(sqlSet.contains("flink_job_id"), "poller must never touch flink_job_id - a concurrent /start owns that column: " + sqlSet);
    }
}
