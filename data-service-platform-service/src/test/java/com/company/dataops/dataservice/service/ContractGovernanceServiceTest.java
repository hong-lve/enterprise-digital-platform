package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.domain.ApiParameter;
import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.ContractReport;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.repository.ContractGovernanceRepository;
import com.company.dataops.dataservice.repository.DataApiRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContractGovernanceServiceTest {
    private DataApiRepository apiRepository;
    private ContractGovernanceRepository reportRepository;
    private ContractGovernanceService service;

    @BeforeEach
    void setUp() {
        apiRepository = mock(DataApiRepository.class);
        reportRepository = mock(ContractGovernanceRepository.class);
        service = new ContractGovernanceService(apiRepository, reportRepository);
        when(apiRepository.findPublishedById(1L)).thenReturn(Optional.of(api()));
        when(reportRepository.save(eq(1L), eq(2), eq(1), any(), any())).thenAnswer(invocation ->
            new ContractReport(
                1L, 1L, 2, 1, invocation.getArgument(3),
                invocation.getArgument(4), Instant.now()
            )
        );
    }

    @Test
    void identifiesRequiredParameterAsBreaking() {
        when(apiRepository.findVersion(1L, 1)).thenReturn(Optional.of(version(
            1, List.of(new ApiParameter("tenant", "QUERY", "STRING", false, null, null)),
            "SELECT id, name FROM orders"
        )));
        when(apiRepository.findVersion(1L, 2)).thenReturn(Optional.of(version(
            2, List.of(
                new ApiParameter("tenant", "QUERY", "STRING", false, null, null),
                new ApiParameter("region", "QUERY", "STRING", true, null, null)
            ),
            "SELECT id, name FROM orders"
        )));

        ContractReport report = service.analyze(1L, 2);

        assertEquals("BREAKING", report.severity());
        assertTrue(report.findings().stream()
            .anyMatch(item -> "REQUIRED_PARAMETER_ADDED".equals(item.code())));
    }

    @Test
    void identifiesRemovedResponseFieldAsBreaking() {
        when(apiRepository.findVersion(1L, 1)).thenReturn(Optional.of(version(
            1, List.of(), "SELECT id, name FROM orders"
        )));
        when(apiRepository.findVersion(1L, 2)).thenReturn(Optional.of(version(
            2, List.of(), "SELECT id FROM orders"
        )));

        ContractReport report = service.analyze(1L, 2);

        assertTrue(report.breaking());
        assertTrue(report.findings().stream()
            .anyMatch(item -> "RESPONSE_FIELD_REMOVED".equals(item.code())));
    }

    private DataApiRecord api() {
        Instant now = Instant.now();
        return new DataApiRecord(
            1L, 2L, "orders", null, "/orders", "GET",
            "SELECT id FROM orders", List.of(), "PUBLISHED", 1,
            "PUBLISHED", 1, 0, 100, now, now, now
        );
    }

    private ApiVersionRecord version(
        int version,
        List<ApiParameter> parameters,
        String sql
    ) {
        return new ApiVersionRecord(
            (long) version, 1L, version, 2L, "orders", null,
            "/orders", "GET", sql, parameters, 0, 100,
            version == 1 ? "PUBLISHED" : "DRAFT", "change",
            "developer", null, null, null, null, null,
            version == 1 ? Instant.now() : null, null, Instant.now()
        );
    }
}
