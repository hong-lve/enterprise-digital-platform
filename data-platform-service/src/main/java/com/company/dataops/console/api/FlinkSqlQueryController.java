package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.service.flink.FlinkSqlGatewayClient;
import com.company.dataops.console.service.flink.FlinkSqlQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/realtime/flink-sql")
public class FlinkSqlQueryController {
    private final FlinkSqlQueryService flinkSqlQueryService;

    public FlinkSqlQueryController(FlinkSqlQueryService flinkSqlQueryService) {
        this.flinkSqlQueryService = flinkSqlQueryService;
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAuthority('realtime:flink-sql:execute')")
    public ApiResponse<FlinkSqlGatewayClient.QueryResult> execute(@Valid @RequestBody QueryRequest request) {
        return ApiResponse.ok(flinkSqlQueryService.execute(request.sql()));
    }

    public record QueryRequest(@NotBlank(message = "SQL 不能为空") String sql) {
    }
}
