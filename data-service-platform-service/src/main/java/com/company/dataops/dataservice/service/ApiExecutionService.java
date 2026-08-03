package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.ApiParameter;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.DatasetAccessPolicy;
import com.company.dataops.dataservice.domain.DatasetRecord;
import com.company.dataops.dataservice.domain.ExecutionResult;
import com.company.dataops.dataservice.repository.CallLogRepository;
import com.company.dataops.dataservice.repository.DatasetAccessPolicyRepository;
import com.company.dataops.dataservice.repository.DatasetRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiExecutionService {
    private final NamedParameterJdbcTemplate queryTemplate;
    private final ManagedDataSourceService managedDataSourceService;
    private final DatasetRepository datasetRepository;
    private final DatasetAccessPolicyRepository accessPolicyRepository;
    private final CallLogRepository callLogRepository;
    private final SqlSecurityPolicy sqlSecurityPolicy;
    private final DataMaskingService dataMaskingService;
    private final QueryCacheService queryCacheService;
    private final ApiResilienceService resilienceService;
    private final ApiMetricsService metricsService;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final int platformMaxPageSize;

    public ApiExecutionService(
        NamedParameterJdbcTemplate queryTemplate,
        ManagedDataSourceService managedDataSourceService,
        DatasetRepository datasetRepository,
        DatasetAccessPolicyRepository accessPolicyRepository,
        CallLogRepository callLogRepository,
        SqlSecurityPolicy sqlSecurityPolicy,
        DataMaskingService dataMaskingService,
        QueryCacheService queryCacheService,
        ApiResilienceService resilienceService,
        ApiMetricsService metricsService,
        ObservationRegistry observationRegistry,
        Tracer tracer,
        @Value("${platform.data-service.max-page-size:500}") int platformMaxPageSize
    ) {
        this.queryTemplate = queryTemplate;
        this.managedDataSourceService = managedDataSourceService;
        this.datasetRepository = datasetRepository;
        this.accessPolicyRepository = accessPolicyRepository;
        this.callLogRepository = callLogRepository;
        this.sqlSecurityPolicy = sqlSecurityPolicy;
        this.dataMaskingService = dataMaskingService;
        this.queryCacheService = queryCacheService;
        this.resilienceService = resilienceService;
        this.metricsService = metricsService;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
        this.platformMaxPageSize = platformMaxPageSize;
    }

    public void validateDefinition(DataApiRecord api) {
        DatasetRecord dataset = datasetRepository.findById(api.datasetId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "关联的数据集不存在"));
        validateSql(api, dataset, accessPolicyRepository.findByDatasetId(dataset.id()));
    }

    public void validateDefinition(long datasetId, String sql, List<ApiParameter> parameters) {
        DatasetRecord dataset = datasetRepository.findById(datasetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "关联的数据集不存在"));
        validateDataset(dataset);
        DatasetAccessPolicy policy = accessPolicyRepository.findByDatasetId(dataset.id());
        sqlSecurityPolicy.secureAndValidate(
            sql, dataset.tableName(), parameters, policy.rowFilterSql()
        );
    }

    public ExecutionResult execute(
        DataApiRecord api,
        Map<String, Object> input,
        Integer requestedPage,
        Integer requestedPageSize,
        String appKey,
        String clientIp,
        boolean testCall
    ) {
        return executeInternal(
            api, input, requestedPage, requestedPageSize, appKey, clientIp, testCall, null
        );
    }

    public ExecutionResult executeRouted(
        DataApiRecord api,
        Map<String, Object> input,
        Integer requestedPage,
        Integer requestedPageSize,
        String appKey,
        String clientIp,
        boolean testCall,
        Long rolloutId,
        String rolloutVariant
    ) {
        return executeInternal(
            api,
            input,
            requestedPage,
            requestedPageSize,
            appKey,
            clientIp,
            testCall,
            new RoutingMetadata(api.version(), rolloutId, rolloutVariant)
        );
    }

    private ExecutionResult executeInternal(
        DataApiRecord api,
        Map<String, Object> input,
        Integer requestedPage,
        Integer requestedPageSize,
        String appKey,
        String clientIp,
        boolean testCall,
        RoutingMetadata routing
    ) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String traceId = currentTraceId();
        long startedAt = System.nanoTime();
        int statusCode = 200;
        Integer rowCount = null;
        try {
            DatasetRecord dataset = datasetRepository.findById(api.datasetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "关联的数据集不存在"));
            DatasetAccessPolicy accessPolicy = accessPolicyRepository.findByDatasetId(dataset.id());
            String securedSql = validateSql(api, dataset, accessPolicy);

            int page = Math.max(requestedPage == null ? 1 : requestedPage, 1);
            int allowedPageSize = Math.min(
                api.maxPageSize() == null ? platformMaxPageSize : api.maxPageSize(),
                platformMaxPageSize
            );
            int pageSize = Math.max(1, Math.min(requestedPageSize == null ? 20 : requestedPageSize, allowedPageSize));

            Map<String, Object> parameters = bindParameters(api.parameters(), input);
            parameters.put("_appKey", appKey);
            parameters.put("_clientIp", clientIp);
            parameters.put("_limit", pageSize);
            parameters.put("_offset", (page - 1) * pageSize);
            NamedParameterJdbcTemplate executionTemplate = resolveTemplate(dataset);
            long policyVersion = accessPolicy.updatedAt() == null
                ? 0
                : accessPolicy.updatedAt().toEpochMilli();
            QueryCacheService.CacheOutcome cacheOutcome = queryCacheService.getOrLoad(
                new QueryCacheService.CacheRequest(
                    api.id(),
                    api.version(),
                    policyVersion,
                    api.cacheTtlSeconds(),
                    appKey,
                    clientIp,
                    page,
                    pageSize,
                    parameters
                ),
                () -> resilienceService.execute(api.id(), () ->
                    Observation.createNotStarted("data.service.query", observationRegistry)
                        .lowCardinalityKeyValue("api.id", String.valueOf(api.id()))
                        .lowCardinalityKeyValue("source.type", dataset.sourceType())
                        .observe(() -> {
                            List<Map<String, Object>> queried = executionTemplate.queryForList(
                                pagedSql(securedSql, dataset.sourceType()),
                                parameters
                            );
                            return dataMaskingService.apply(queried, accessPolicy.columns());
                        })
                )
            );
            List<Map<String, Object>> rows = cacheOutcome.rows();
            rowCount = rows.size();
            long elapsedMs = elapsedMillis(startedAt);
            saveCallLog(
                api, routing, requestId, traceId, appKey, statusCode, elapsedMs,
                rowCount, testCall, clientIp, null
            );
            metricsService.record(
                api.id(), statusCode, elapsedMs, cacheOutcome.status(), cacheOutcome.degraded()
            );
            return new ExecutionResult(
                requestId,
                traceId,
                api.id(),
                api.name(),
                page,
                pageSize,
                rowCount,
                elapsedMs,
                cacheOutcome.status(),
                cacheOutcome.degraded(),
                rows
            );
        } catch (RuntimeException exception) {
            statusCode = exception instanceof ResponseStatusException response
                ? response.getStatusCode().value()
                : 500;
            saveCallLog(
                api, routing, requestId, traceId, appKey, statusCode, elapsedMillis(startedAt),
                rowCount, testCall, clientIp, exception.getMessage()
            );
            metricsService.record(api.id(), statusCode, elapsedMillis(startedAt), null, false);
            throw exception;
        }
    }

    private void saveCallLog(
        DataApiRecord api,
        RoutingMetadata routing,
        String requestId,
        String traceId,
        String appKey,
        int statusCode,
        long elapsedMs,
        Integer rowCount,
        boolean testCall,
        String clientIp,
        String errorMessage
    ) {
        if (routing == null) {
            callLogRepository.save(
                api.id(), requestId, traceId, appKey, api.path(), api.method(), statusCode,
                elapsedMs, rowCount, testCall, clientIp, errorMessage
            );
            return;
        }
        callLogRepository.save(
            api.id(), routing.versionNo(), routing.rolloutId(), routing.variant(),
            requestId, traceId, appKey, api.path(), api.method(), statusCode, elapsedMs,
            rowCount, testCall, clientIp, errorMessage
        );
    }

    private record RoutingMetadata(Integer versionNo, Long rolloutId, String variant) {
    }

    private String validateSql(
        DataApiRecord api,
        DatasetRecord dataset,
        DatasetAccessPolicy accessPolicy
    ) {
        validateDataset(dataset);
        return sqlSecurityPolicy.secureAndValidate(
            api.querySql(),
            dataset.tableName(),
            api.parameters(),
            accessPolicy.rowFilterSql()
        );
    }

    private void validateDataset(DatasetRecord dataset) {
        if ("MANAGED".equalsIgnoreCase(dataset.connectionMode()) && dataset.connectionId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "数据集未绑定受管数据源");
        }
        if (!Set.of("MYSQL", "DORIS", "CLICKHOUSE", "ORACLE").contains(dataset.sourceType().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "当前查询执行器不支持该数据源类型");
        }
    }

    private NamedParameterJdbcTemplate resolveTemplate(DatasetRecord dataset) {
        if ("PLATFORM".equalsIgnoreCase(dataset.connectionMode())) {
            if (!"MYSQL".equalsIgnoreCase(dataset.sourceType())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "平台内置连接仅支持 MySQL");
            }
            return queryTemplate;
        }
        return managedDataSourceService.queryTemplate(dataset.connectionId());
    }

    private String pagedSql(String sql, String sourceType) {
        if ("ORACLE".equalsIgnoreCase(sourceType)) {
            return sql + " OFFSET :_offset ROWS FETCH NEXT :_limit ROWS ONLY";
        }
        return sql + " LIMIT :_limit OFFSET :_offset";
    }

    private Map<String, Object> bindParameters(List<ApiParameter> definitions, Map<String, Object> input) {
        Map<String, Object> bound = new HashMap<>();
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        for (ApiParameter definition : definitions) {
            Object raw = safeInput.get(definition.name());
            if ((raw == null || String.valueOf(raw).isBlank()) && definition.defaultValue() != null) {
                raw = definition.defaultValue();
            }
            if ((raw == null || String.valueOf(raw).isBlank()) && definition.required()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少必填参数：" + definition.name());
            }
            bound.put(definition.name(), convert(definition, raw));
        }
        return bound;
    }

    private Object convert(ApiParameter definition, Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        String value = String.valueOf(raw);
        String type = definition.type() == null ? "STRING" : definition.type().toUpperCase(Locale.ROOT);
        try {
            return switch (type) {
                case "INTEGER" -> Integer.valueOf(value);
                case "LONG" -> Long.valueOf(value);
                case "DECIMAL" -> new BigDecimal(value);
                case "BOOLEAN" -> parseBoolean(value);
                case "DATE" -> LocalDate.parse(value);
                case "DATETIME" -> LocalDateTime.parse(value);
                default -> value;
            };
        } catch (RuntimeException exception) {
            if (exception instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "参数 " + definition.name() + " 不是有效的 " + type
            );
        }
    }

    private Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("不是有效的布尔值");
    }

    public Map<String, Object> collectRuntimeInput(
        DataApiRecord api,
        Map<String, String> query,
        Map<String, String> headers,
        Object body
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (query != null) {
            input.putAll(query);
        }
        if (body instanceof Map<?, ?> bodyMap) {
            bodyMap.forEach((key, value) -> input.put(String.valueOf(key), value));
        }
        for (ApiParameter parameter : api.parameters()) {
            if ("HEADER".equalsIgnoreCase(parameter.location()) && headers != null) {
                String headerValue = headers.get(parameter.name().toLowerCase(Locale.ROOT));
                if (headerValue != null) {
                    input.put(parameter.name(), headerValue);
                }
            }
        }
        return input;
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String currentTraceId() {
        io.micrometer.tracing.Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
