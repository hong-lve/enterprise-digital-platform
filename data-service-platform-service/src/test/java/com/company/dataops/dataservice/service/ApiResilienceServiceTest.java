package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ApiResilienceServiceTest {
    @Test
    void opensCircuitAfterThresholdAndRecoversWithProbe() throws Exception {
        ApiResilienceService service = new ApiResilienceService(
            2,
            2,
            Duration.ofMillis(20)
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

        Thread.sleep(30);
        assertEquals("recovered", service.execute(9L, () -> "recovered"));
        assertEquals("CLOSED", service.metrics().circuits().get(0).status());
    }

    private String fail(AtomicInteger invocations) {
        invocations.incrementAndGet();
        throw new IllegalStateException("database unavailable");
    }
}
