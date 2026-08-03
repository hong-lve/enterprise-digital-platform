package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.domain.ApiRolloutRecord;
import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.RolloutHealthPolicy;
import com.company.dataops.dataservice.domain.RolloutHealthSnapshot;
import com.company.dataops.dataservice.domain.RolloutStage;
import com.company.dataops.dataservice.repository.ApiRolloutRepository;
import com.company.dataops.dataservice.repository.ApplicationRepository;
import com.company.dataops.dataservice.repository.DataApiRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ApiRolloutServiceTest {
    private ApiRolloutRepository rolloutRepository;
    private DataApiRepository apiRepository;
    private ApiRolloutService service;

    @BeforeEach
    void setUp() {
        rolloutRepository = mock(ApiRolloutRepository.class);
        apiRepository = mock(DataApiRepository.class);
        service = new ApiRolloutService(
            rolloutRepository,
            apiRepository,
            mock(ApplicationRepository.class),
            new ApiLifecyclePolicy(),
            mock(ApiReleaseGateService.class),
            mock(NotificationService.class)
        );
    }

    @Test
    void routesTargetApplicationToCandidateVersion() {
        DataApiRecord baseline = baseline();
        when(rolloutRepository.findActive(9L))
            .thenReturn(Optional.of(rollout(Set.of(42L), List.of(), 0)));
        when(apiRepository.findVersion(9L, 2))
            .thenReturn(Optional.of(candidate()));

        ApiRolloutService.RouteDecision result =
            service.route(baseline, 42L, "consumer-a", "192.168.1.8");

        assertEquals("CANARY", result.variant());
        assertEquals(2, result.api().version());
        assertEquals(15L, result.rolloutId());
    }

    @Test
    void supportsCidrTargetingAndKeepsOtherTrafficStable() {
        DataApiRecord baseline = baseline();
        when(rolloutRepository.findActive(9L))
            .thenReturn(Optional.of(rollout(Set.of(), List.of("10.20.0.0/16"), 0)));
        when(apiRepository.findVersion(9L, 2))
            .thenReturn(Optional.of(candidate()));

        assertEquals(
            "CANARY",
            service.route(baseline, 7L, "consumer-b", "10.20.8.9").variant()
        );
        assertEquals(
            "STABLE",
            service.route(baseline, 7L, "consumer-b", "10.21.8.9").variant()
        );
    }

    @Test
    void rejectsFullTrafficAsCanaryAllocation() {
        assertThrows(
            ResponseStatusException.class,
            () -> service.update(15L, 100, Set.of(), List.of(), null, "approver")
        );
    }

    @Test
    void advancesAutomatedRolloutWhenHealthGatePasses() {
        ApiRolloutRecord rollout = automatedRollout("PAUSE");
        when(rolloutRepository.ownsLock(15L, "worker-a")).thenReturn(true);
        when(rolloutRepository.findById(15L)).thenReturn(Optional.of(rollout));
        when(rolloutRepository.health(15L, rollout.stageStartedAt()))
            .thenReturn(health(120, 99.2, 0.8, 180, 260));

        service.evaluateAutomated(15L, "worker-a");

        verify(rolloutRepository).advanceStage(
            org.mockito.ArgumentMatchers.eq(15L),
            org.mockito.ArgumentMatchers.eq(1),
            org.mockito.ArgumentMatchers.eq(25),
            org.mockito.ArgumentMatchers.any(Instant.class),
            org.mockito.ArgumentMatchers.eq("canary-scheduler")
        );
    }

    @Test
    void rollsBackAutomaticallyWhenHealthGateFails() {
        ApiRolloutRecord rollout = automatedRollout("ROLLBACK");
        when(rolloutRepository.ownsLock(15L, "worker-a")).thenReturn(true);
        when(rolloutRepository.findById(15L)).thenReturn(Optional.of(rollout));
        when(rolloutRepository.health(15L, rollout.stageStartedAt()))
            .thenReturn(health(120, 92.0, 8.0, 180, 260));

        service.evaluateAutomated(15L, "worker-a");

        verify(apiRepository).archiveCanary(9L, 2, "canary-scheduler");
        verify(rolloutRepository).finish(15L, "ROLLED_BACK", "canary-scheduler");
    }

    @Test
    void pausesInsteadOfRollingBackWhenSampleIsInsufficient() {
        ApiRolloutRecord rollout = automatedRollout("ROLLBACK");
        when(rolloutRepository.ownsLock(15L, "worker-a")).thenReturn(true);
        when(rolloutRepository.findById(15L)).thenReturn(Optional.of(rollout));
        when(rolloutRepository.health(15L, rollout.stageStartedAt()))
            .thenReturn(health(10, 100.0, 0.0, 80, 100));

        service.evaluateAutomated(15L, "worker-a");

        verify(rolloutRepository).pause(
            org.mockito.ArgumentMatchers.eq(15L),
            org.mockito.ArgumentMatchers.contains("Insufficient sample size"),
            org.mockito.ArgumentMatchers.eq("canary-scheduler")
        );
    }

    private DataApiRecord baseline() {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        return new DataApiRecord(
            9L, 3L, "orders", null, "/orders", "GET", "SELECT id FROM orders",
            List.of(), "PUBLISHED", 1, "PENDING_APPROVAL", 1, 0, 100,
            now, now, now
        );
    }

    private ApiVersionRecord candidate() {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        return new ApiVersionRecord(
            22L, 9L, 2, 3L, "orders", null, "/orders", "GET",
            "SELECT id FROM orders", List.of(), 0, 100, "CANARY", "change",
            "developer", "developer", now, "approver", now, null, null, null, now
        );
    }

    private ApiRolloutRecord rollout(Set<Long> applications, List<String> ipRules, int percentage) {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        return new ApiRolloutRecord(
            15L, 9L, 1, 2, percentage,
            false, List.of(), 0, now, null, null, "PAUSE",
            null, null, null,
            applications, ipRules, "ACTIVE", null,
            "approver", now, "approver", now, null, null
        );
    }

    private ApiRolloutRecord automatedRollout(String failureAction) {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        return new ApiRolloutRecord(
            15L, 9L, 1, 2, 5,
            true,
            List.of(
                new RolloutStage(5, 1),
                new RolloutStage(25, 1),
                new RolloutStage(100, 0)
            ),
            0,
            now,
            now.plusSeconds(60),
            new RolloutHealthPolicy(100, 99.0, 1.0, 500L, 800L),
            failureAction,
            null,
            null,
            null,
            Set.of(),
            List.of(),
            "ACTIVE",
            "automated",
            "approver",
            now,
            "approver",
            now,
            null,
            null
        );
    }

    private RolloutHealthSnapshot health(
        long requests,
        double successRate,
        double errorRate,
        long p95,
        long p99
    ) {
        long successes = Math.round(requests * successRate / 100.0);
        return new RolloutHealthSnapshot(
            requests,
            successes,
            requests - successes,
            successRate,
            errorRate,
            100.0,
            p95,
            p99,
            Instant.parse("2026-07-28T00:00:00Z")
        );
    }
}
