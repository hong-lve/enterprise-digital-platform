package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.function.Supplier;

class ApiResilienceServiceTest {
    @Test
    void opensCircuitAfterThresholdAndRecoversWithProbe() throws Exception {
        ApiResilienceService service = new ApiResilienceService(
            2,
            2,
            Duration.ofMillis(200)
        );
        AtomicInteger invocations = new AtomicInteger();

        assertThrows(IllegalStateException.class, () ->
            service.execute(9L, () -> fail(invocations)));
        assertThrows(IllegalStateException.class, () ->
            service.execute(9L, () -> fail(invocations)));
        assertThrows(ResponseStatusException.class, () ->
            service.execute(9L, () -> "blocked"));
        assertEquals(2, invocations.get());
        assertEquals("OPEN", service.metrics().circuits().get(0).status());

        Thread.sleep(250);
        assertEquals("recovered", service.execute(9L, () -> "recovered"));
        assertEquals("CLOSED", service.metrics().circuits().get(0).status());
    }

    @Test
    void rejectsBeforeInvokingSupplierWhenGlobalConcurrencyIsFull() {
        DistributedCircuitBreakerStore circuitStore = mock(DistributedCircuitBreakerStore.class);
        DistributedConcurrencyStore concurrencyStore = mock(DistributedConcurrencyStore.class);
        when(circuitStore.acquire(12L, Duration.ofSeconds(30)))
            .thenReturn(DistributedCircuitBreakerStore.Permit.ALLOW);
        when(concurrencyStore.acquire(12L, 3, Duration.ofSeconds(30)))
            .thenReturn(DistributedConcurrencyStore.Lease.rejected(3));
        ApiResilienceService service = new ApiResilienceService(
            2, 2, Duration.ofSeconds(30), circuitStore, concurrencyStore, 3, Duration.ofSeconds(30)
        );
        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);

        assertThrows(ResponseStatusException.class, () -> service.execute(12L, supplier));
        verify(supplier, never()).get();
        assertEquals(1, service.metrics().globalConcurrencyRejected());
    }

    @Test
    void releasesGlobalConcurrencyLeaseAfterExecution() {
        DistributedCircuitBreakerStore circuitStore = mock(DistributedCircuitBreakerStore.class);
        DistributedConcurrencyStore concurrencyStore = mock(DistributedConcurrencyStore.class);
        DistributedConcurrencyStore.Lease lease = DistributedConcurrencyStore.Lease.acquired("key", "token", 1);
        when(circuitStore.acquire(13L, Duration.ofSeconds(30)))
            .thenReturn(DistributedCircuitBreakerStore.Permit.ALLOW);
        when(concurrencyStore.acquire(13L, 3, Duration.ofSeconds(30))).thenReturn(lease);
        ApiResilienceService service = new ApiResilienceService(
            2, 2, Duration.ofSeconds(30), circuitStore, concurrencyStore, 3, Duration.ofSeconds(30)
        );

        assertEquals("ok", service.execute(13L, () -> "ok"));
        verify(concurrencyStore).release(lease);
    }

    private String fail(AtomicInteger invocations) {
        invocations.incrementAndGet();
        throw new IllegalStateException("database unavailable");
    }
}
