package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.security.ActionRateLimiter;
import com.company.dataops.console.service.flink.FlinkSqlGatewayClient;
import com.company.dataops.console.service.flink.FlinkSqlQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/realtime/flink-sql")
public class FlinkSqlQueryController {
    // Each call submits a real query job to the Flink SQL gateway - cheap to
    // click a few times while iterating on a query, expensive if scripted.
    private static final int MAX_EXECUTIONS_PER_WINDOW = 20;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final FlinkSqlQueryService flinkSqlQueryService;
    private final ActionRateLimiter rateLimiter;

    public FlinkSqlQueryController(FlinkSqlQueryService flinkSqlQueryService, ActionRateLimiter rateLimiter) {
        this.flinkSqlQueryService = flinkSqlQueryService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAuthority('realtime:flink-sql:execute')")
    public ApiResponse<FlinkSqlGatewayClient.QueryResult> execute(@Valid @RequestBody QueryRequest request, Authentication authentication) {
        rateLimiter.assertWithinLimit("ratelimit:flink-sql-execute:" + authentication.getName(), MAX_EXECUTIONS_PER_WINDOW, WINDOW);
        return ApiResponse.ok(flinkSqlQueryService.execute(request.sql()));
    }

    public record QueryRequest(@NotBlank(message = "SQL 不能为空") String sql) {
    }
}
