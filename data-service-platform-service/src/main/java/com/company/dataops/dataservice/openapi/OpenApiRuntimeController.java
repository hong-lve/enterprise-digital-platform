package com.company.dataops.dataservice.openapi;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.ExecutionResult;
import com.company.dataops.dataservice.repository.DataApiRepository;
import com.company.dataops.dataservice.repository.CallLogRepository;
import com.company.dataops.dataservice.security.OpenApiSecurityFilter;
import com.company.dataops.dataservice.service.ApiExecutionService;
import com.company.dataops.dataservice.service.ApiRolloutService;
import com.company.dataops.dataservice.service.ApiSubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/openapi")
public class OpenApiRuntimeController {
    private final DataApiRepository apiRepository;
    private final CallLogRepository callLogRepository;
    private final ApiExecutionService executionService;
    private final ApiSubscriptionService subscriptionService;
    private final ApiRolloutService rolloutService;

    public OpenApiRuntimeController(
        DataApiRepository apiRepository,
        CallLogRepository callLogRepository,
        ApiExecutionService executionService,
        ApiSubscriptionService subscriptionService,
        ApiRolloutService rolloutService
    ) {
        this.apiRepository = apiRepository;
        this.callLogRepository = callLogRepository;
        this.executionService = executionService;
        this.subscriptionService = subscriptionService;
        this.rolloutService = rolloutService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
            "service", "data-service-platform-service",
            "time", Instant.now().toString()
        ));
    }

    @RequestMapping("/**")
    public ApiResponse<ExecutionResult> dispatch(
        HttpServletRequest request,
        HttpServletResponse response,
        @RequestParam MultiValueMap<String, String> queryParameters,
        @RequestHeader Map<String, String> requestHeaders,
        @RequestBody(required = false) Object requestBody
    ) {
        String path = request.getRequestURI().substring("/openapi".length());
        DataApiRecord api = apiRepository.findPublished(path, request.getMethod()).orElse(null);
        if (api == null) {
            auditRejected(null, path, request, 404, "API 未发布或不存在");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API 未发布或不存在");
        }
        Object appIdAttribute = request.getAttribute(OpenApiSecurityFilter.ATTR_APP_ID);
        if (!(appIdAttribute instanceof Long appId)) {
            auditRejected(api.id(), path, request, 403, "应用未获得此 API 的调用权限");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "应用未获得此 API 的调用权限");
        }
        try {
            ApiSubscriptionService.RuntimeQuota quota = subscriptionService.authorize(
                appId,
                api.id(),
                clientIp(request)
            );
            response.setHeader("X-Subscription-Id", String.valueOf(quota.subscriptionId()));
            response.setHeader("X-Subscription-RateLimit-Limit", String.valueOf(quota.qpsLimit()));
            response.setHeader("X-Subscription-RateLimit-Remaining", String.valueOf(quota.qpsRemaining()));
            response.setHeader("X-DailyLimit-Limit", String.valueOf(quota.dailyLimit()));
            response.setHeader("X-DailyLimit-Remaining", String.valueOf(quota.dailyRemaining()));
        } catch (ResponseStatusException exception) {
            auditRejected(api.id(), path, request, exception.getStatusCode().value(), exception.getReason());
            throw exception;
        }

        String appKey = String.valueOf(request.getAttribute(OpenApiSecurityFilter.ATTR_APP_KEY));
        String clientIp = clientIp(request);
        ApiRolloutService.RouteDecision route = rolloutService.route(
            api, appId, appKey, clientIp
        );
        api = route.api();
        response.setHeader("X-API-Version", String.valueOf(api.version()));
        response.setHeader("X-Release-Variant", route.variant());
        if (route.rolloutId() != null) {
            response.setHeader("X-Rollout-Id", String.valueOf(route.rolloutId()));
        }

        Map<String, String> query = new LinkedHashMap<>();
        queryParameters.forEach((name, values) -> {
            if (!values.isEmpty()) {
                query.put(name, values.get(0));
            }
        });
        Map<String, String> headers = new LinkedHashMap<>();
        requestHeaders.forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT), value));
        Map<String, Object> input = executionService.collectRuntimeInput(api, query, headers, requestBody);
        return ApiResponse.ok(executionService.executeRouted(
            api,
            input,
            parseInteger(query.get("page")),
            parseInteger(query.get("pageSize")),
            appKey,
            clientIp,
            false,
            route.rolloutId(),
            route.variant()
        ));
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数必须是整数");
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",")[0].trim();
    }

    private void auditRejected(
        Long apiId,
        String path,
        HttpServletRequest request,
        int status,
        String message
    ) {
        callLogRepository.save(
            apiId,
            UUID.randomUUID().toString().replace("-", ""),
            String.valueOf(request.getAttribute(OpenApiSecurityFilter.ATTR_APP_KEY)),
            path,
            request.getMethod(),
            status,
            0,
            null,
            false,
            clientIp(request),
            message
        );
    }
}
