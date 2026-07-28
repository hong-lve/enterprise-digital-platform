package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.repository.DataApiRepository;
import com.company.dataops.dataservice.service.ApiResilienceService;
import com.company.dataops.dataservice.service.QueryCacheService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/data-service-admin/runtime")
public class RuntimeOperationsController {
    private final QueryCacheService queryCacheService;
    private final ApiResilienceService resilienceService;
    private final DataApiRepository apiRepository;

    public RuntimeOperationsController(
        QueryCacheService queryCacheService,
        ApiResilienceService resilienceService,
        DataApiRepository apiRepository
    ) {
        this.queryCacheService = queryCacheService;
        this.resilienceService = resilienceService;
        this.apiRepository = apiRepository;
    }

    @GetMapping("/metrics")
    public ApiResponse<RuntimeSnapshot> metrics() {
        return ApiResponse.ok(new RuntimeSnapshot(
            queryCacheService.metrics(),
            resilienceService.metrics()
        ));
    }

    @PostMapping("/cache/apis/{apiId}/evict")
    public ApiResponse<Map<String, Object>> evictApiCache(@PathVariable long apiId) {
        if (apiRepository.findById(apiId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API 不存在");
        }
        return ApiResponse.ok(Map.of(
            "apiId", apiId,
            "cacheEpoch", queryCacheService.evictApi(apiId)
        ));
    }

    public record RuntimeSnapshot(
        QueryCacheService.CacheMetrics cache,
        ApiResilienceService.ResilienceMetrics resilience
    ) {
    }
}
