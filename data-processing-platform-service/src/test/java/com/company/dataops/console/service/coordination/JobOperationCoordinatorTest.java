package com.company.dataops.console.service.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.console.mapper.JobOperationRequestMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class JobOperationCoordinatorTest {
    private final PlatformLeaseService leaseService = mock(PlatformLeaseService.class);
    private final JobOperationRequestMapper requestMapper = mock(JobOperationRequestMapper.class);
    private final JobOperationCoordinator coordinator = new JobOperationCoordinator(leaseService, requestMapper);

    @Test
    void rejectsConcurrentOperationBeforeSubmittingJob() {
        when(leaseService.tryAcquire("job-operation:FLINK_STREAM_JOB:9", Duration.ofMinutes(5)))
            .thenReturn(Optional.empty());
        AtomicInteger submissions = new AtomicInteger();

        assertThrows(ResponseStatusException.class, () -> coordinator.execute(
            "FLINK_STREAM_JOB", 9L, "START", "request-1", Duration.ofMinutes(5), submissions::incrementAndGet));

        assertEquals(0, submissions.get());
    }

    @Test
    void executesAnIdempotentRequestOnlyOnce() {
        var lease = new PlatformLeaseService.Lease("job-operation:FLINK_SQL_JOB:3", "instance-a", 17L);
        when(leaseService.tryAcquire(lease.name(), Duration.ofMinutes(5))).thenReturn(Optional.of(lease));
        when(requestMapper.register("same-request", "FLINK_SQL_JOB", 3L, "START", 17L))
            .thenReturn(1, 0);
        AtomicInteger submissions = new AtomicInteger();

        assertEquals(1, coordinator.execute("FLINK_SQL_JOB", 3L, "START", "same-request",
            Duration.ofMinutes(5), submissions::incrementAndGet));
        assertThrows(ResponseStatusException.class, () -> coordinator.execute("FLINK_SQL_JOB", 3L, "START",
            "same-request", Duration.ofMinutes(5), submissions::incrementAndGet));

        assertEquals(1, submissions.get());
        verify(requestMapper).markSucceeded("same-request", 17L);
    }
}
