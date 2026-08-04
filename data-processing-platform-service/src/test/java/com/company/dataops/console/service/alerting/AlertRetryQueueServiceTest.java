package com.company.dataops.console.service.alerting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.dataops.console.entity.AlertRetryQueueEntity;
import com.company.dataops.console.mapper.AlertRetryQueueMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertRetryQueueServiceTest {
    @Test
    void returnsOnlyRowsClaimedByThisInstance() {
        AlertRetryQueueMapper mapper = mock(AlertRetryQueueMapper.class);
        AlertRetryQueueEntity first = entry(1L);
        AlertRetryQueueEntity second = entry(2L);
        when(mapper.selectClaimCandidates(100)).thenReturn(List.of(first, second));
        when(mapper.claim(eq(1L), anyString(), anyInt())).thenReturn(1);
        when(mapper.claim(eq(2L), anyString(), anyInt())).thenReturn(0);

        List<AlertRetryQueueEntity> claimed = new AlertRetryQueueService(mapper).claimDue(100);

        assertEquals(List.of(1L), claimed.stream().map(AlertRetryQueueEntity::getId).toList());
    }

    private AlertRetryQueueEntity entry(Long id) {
        AlertRetryQueueEntity entry = new AlertRetryQueueEntity();
        entry.setId(id);
        return entry;
    }
}
