package com.company.dataops.console.service.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.console.entity.RecoveryStateEntity;
import com.company.dataops.console.entity.RecoveryEventEntity;
import com.company.dataops.console.mapper.RecoveryEventMapper;
import com.company.dataops.console.mapper.RecoveryStateMapper;
import org.junit.jupiter.api.Test;

class RecoveryOrchestratorTest {
    @Test
    void onlyProceedsWhenDatabaseLeaseIsAcquired() {
        RecoveryStateMapper stateMapper = mock(RecoveryStateMapper.class);
        RecoveryEventMapper eventMapper = mock(RecoveryEventMapper.class);
        RecoveryStateEntity state = state(7L);
        when(stateMapper.selectOne(any())).thenReturn(state);
        when(stateMapper.acquireLease(any(), anyString(), anyInt())).thenReturn(1);

        RecoveryOrchestrator orchestrator = new RecoveryOrchestrator(stateMapper, eventMapper);

        assertTrue(orchestrator.tryAcquire("FLINK_JOB", 9L, "orders"));
        verify(eventMapper).insert(any(RecoveryEventEntity.class));
    }

    @Test
    void skipsRecoveryWhenAnotherInstanceOwnsLease() {
        RecoveryStateMapper stateMapper = mock(RecoveryStateMapper.class);
        RecoveryStateEntity state = state(7L);
        when(stateMapper.selectOne(any())).thenReturn(state);
        when(stateMapper.acquireLease(any(), anyString(), anyInt())).thenReturn(0);

        RecoveryOrchestrator orchestrator = new RecoveryOrchestrator(stateMapper, mock(RecoveryEventMapper.class));

        assertFalse(orchestrator.tryAcquire("FLINK_JOB", 9L, "orders"));
    }

    private RecoveryStateEntity state(Long id) {
        RecoveryStateEntity state = new RecoveryStateEntity();
        state.setId(id);
        state.setTier(1);
        state.setAttemptsInTier(0);
        state.setCircuitState("OK");
        return state;
    }
}
