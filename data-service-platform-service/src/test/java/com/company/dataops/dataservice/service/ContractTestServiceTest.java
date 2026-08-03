package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.ContractAssertion;
import com.company.dataops.dataservice.domain.ContractTestCase;
import com.company.dataops.dataservice.domain.ContractTestRun;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.ExecutionResult;
import com.company.dataops.dataservice.repository.ContractTestRepository;
import com.company.dataops.dataservice.repository.DataApiRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContractTestServiceTest {
    private ContractTestRepository repository;
    private ApiExecutionService executionService;
    private ContractTestService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContractTestRepository.class);
        DataApiRepository apiRepository = mock(DataApiRepository.class);
        executionService = mock(ApiExecutionService.class);
        service = new ContractTestService(repository, apiRepository, executionService);
        when(apiRepository.findById(1L)).thenReturn(Optional.of(api()));
        when(apiRepository.findVersion(1L, 2)).thenReturn(Optional.of(version()));
        when(repository.saveRun(
            anyLong(), anyLong(), anyInt(), anyString(), any(), any(), any(), anyString()
        )).thenAnswer(invocation -> new ContractTestRun(
            1L,
            invocation.getArgument(0),
            invocation.getArgument(1),
            invocation.getArgument(2),
            invocation.getArgument(3),
            invocation.getArgument(4),
            invocation.getArgument(5),
            invocation.getArgument(6),
            invocation.getArgument(7),
            Instant.now()
        ));
    }

    @Test
    void passesMatchingFieldAndLatencyAssertions() {
        ContractTestCase testCase = testCase(List.of(
            new ContractAssertion("FIELD_TYPE", "id", "INTEGER"),
            new ContractAssertion("MAX_ELAPSED_MS", null, "500")
        ));
        when(repository.findCase(3L)).thenReturn(Optional.of(testCase));
        when(executionService.execute(any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(result(List.of(Map.of("id", 10L)), 20));

        ContractTestRun run = service.runCase(1L, 3L, 2, "approver");

        assertEquals("PASSED", run.status());
    }

    @Test
    void failsWhenExpectedFieldIsMissing() {
        when(repository.findCase(3L)).thenReturn(Optional.of(testCase(List.of(
            new ContractAssertion("FIELD_EXISTS", "name", null)
        ))));
        when(executionService.execute(any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(result(List.of(Map.of("id", 10L)), 20));

        ContractTestRun run = service.runCase(1L, 3L, 2, "approver");

        assertEquals("FAILED", run.status());
    }

    private ContractTestCase testCase(List<ContractAssertion> assertions) {
        return new ContractTestCase(
            3L, 1L, "smoke", true, Map.of(), 1, 20,
            assertions, "developer", Instant.now(), Instant.now()
        );
    }

    private ExecutionResult result(List<Map<String, Object>> rows, long elapsed) {
        return new ExecutionResult(
            "request", "trace", 1L, "orders", 1, 20,
            rows.size(), elapsed, "BYPASS", false, rows
        );
    }

    private DataApiRecord api() {
        Instant now = Instant.now();
        return new DataApiRecord(
            1L, 2L, "orders", null, "/orders", "GET",
            "SELECT id FROM orders", List.of(), "PUBLISHED", 2,
            "DRAFT", 1, 0, 100, now, now, now
        );
    }

    private ApiVersionRecord version() {
        return new ApiVersionRecord(
            2L, 1L, 2, 2L, "orders", null, "/orders", "GET",
            "SELECT id FROM orders", List.of(), 0, 100, "DRAFT",
            "change", "developer", null, null, null, null, null,
            null, null, Instant.now()
        );
    }
}
