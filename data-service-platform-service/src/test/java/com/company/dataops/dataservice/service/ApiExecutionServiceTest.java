package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.domain.ApiParameter;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.DatasetAccessPolicy;
import com.company.dataops.dataservice.domain.DatasetRecord;
import com.company.dataops.dataservice.repository.CallLogRepository;
import com.company.dataops.dataservice.repository.DatasetAccessPolicyRepository;
import com.company.dataops.dataservice.repository.DatasetRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ApiExecutionServiceTest {
    private DatasetRepository datasetRepository;
    private DatasetAccessPolicyRepository accessPolicyRepository;
    private ApiExecutionService service;

    @BeforeEach
    void setUp() {
        datasetRepository = mock(DatasetRepository.class);
        accessPolicyRepository = mock(DatasetAccessPolicyRepository.class);
        service = new ApiExecutionService(
            mock(NamedParameterJdbcTemplate.class),
            mock(ManagedDataSourceService.class),
            datasetRepository,
            accessPolicyRepository,
            mock(CallLogRepository.class),
            new SqlSecurityPolicy(1, 0, true),
            new DataMaskingService(),
            mock(QueryCacheService.class),
            mock(ApiResilienceService.class),
            mock(ApiMetricsService.class),
            io.micrometer.observation.ObservationRegistry.NOOP,
            mock(io.micrometer.tracing.Tracer.class),
            500
        );
        when(datasetRepository.findById(1L)).thenReturn(Optional.of(dataset()));
        when(accessPolicyRepository.findByDatasetId(1L)).thenReturn(
            new DatasetAccessPolicy(1L, null, List.of(), null, null)
        );
    }

    @Test
    void acceptsSafeSelectWithDeclaredParameter() {
        assertDoesNotThrow(() -> service.validateDefinition(api(
            "SELECT id, api_path FROM data_service_call_log WHERE api_path = :apiPath",
            List.of(parameter("apiPath"))
        )));
    }

    @Test
    void rejectsMutationSql() {
        assertThrows(ResponseStatusException.class, () -> service.validateDefinition(api(
            "DELETE FROM data_service_call_log WHERE api_path = :apiPath",
            List.of(parameter("apiPath"))
        )));
    }

    @Test
    void rejectsUndeclaredSqlParameter() {
        assertThrows(ResponseStatusException.class, () -> service.validateDefinition(api(
            "SELECT id FROM data_service_call_log WHERE api_path = :apiPath",
            List.of()
        )));
    }

    @Test
    void rejectsQueryOutsideRegisteredDataset() {
        assertThrows(ResponseStatusException.class, () -> service.validateDefinition(api(
            "SELECT id FROM data_service_api",
            List.of()
        )));
    }

    private DatasetRecord dataset() {
        return new DatasetRecord(
            1L,
            "调用日志",
            null,
            "MYSQL",
            "platform-mysql",
            "PLATFORM",
            null,
            "data_service_call_log",
            "system",
            "ACTIVE",
            Instant.now(),
            Instant.now()
        );
    }

    private DataApiRecord api(String sql, List<ApiParameter> parameters) {
        return new DataApiRecord(
            1L,
            1L,
            "调用日志查询",
            null,
            "/governance/call-logs",
            "GET",
            sql,
            parameters,
            "DRAFT",
            1,
            "DRAFT",
            null,
            0,
            100,
            null,
            Instant.now(),
            Instant.now()
        );
    }

    private ApiParameter parameter(String name) {
        return new ApiParameter(name, "QUERY", "STRING", true, null, null);
    }
}
