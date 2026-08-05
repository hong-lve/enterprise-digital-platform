package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.security.ActionRateLimiter;
import com.company.dataops.console.service.datasource.DataSourceConnectionService;
import com.company.dataops.console.service.query.ColumnView;
import com.company.dataops.console.service.query.QueryResult;
import com.company.dataops.console.service.query.TableView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Driven entirely by a registered DataSourceEntity now instead of two fixed
 * ClickHouse/Doris connections read from application.yml - any MySQL/
 * ClickHouse/Doris data source registered on the 数据源配置 page can be
 * queried here, not just the original two.
 */
@RestController
@RequestMapping("/realtime/query")
public class RealtimeQueryController {
    // Runs arbitrary user SQL against a live registered data source - same
    // reasoning as FlinkSqlQueryController's limit.
    private static final int MAX_EXECUTIONS_PER_WINDOW = 20;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final DataSourceMapper dataSourceMapper;
    private final DataSourceConnectionService connectionService;
    private final ActionRateLimiter rateLimiter;

    public RealtimeQueryController(DataSourceMapper dataSourceMapper, DataSourceConnectionService connectionService, ActionRateLimiter rateLimiter) {
        this.dataSourceMapper = dataSourceMapper;
        this.connectionService = connectionService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/tables")
    @PreAuthorize("hasAuthority('realtime:query:execute')")
    public ApiResponse<List<TableView>> tables(@RequestParam Long dataSourceId, @RequestParam(required = false) String database) {
        DataSourceEntity dataSource = requireDataSource(dataSourceId);
        return ApiResponse.ok(connectionService.tables(dataSource, database));
    }

    @GetMapping("/tables/{table}/columns")
    @PreAuthorize("hasAuthority('realtime:query:execute')")
    public ApiResponse<List<ColumnView>> columns(@PathVariable String table, @RequestParam Long dataSourceId, @RequestParam(required = false) String database) {
        DataSourceEntity dataSource = requireDataSource(dataSourceId);
        return ApiResponse.ok(connectionService.columns(dataSource, database, table));
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAuthority('realtime:query:execute')")
    public ApiResponse<QueryResult> execute(@Valid @RequestBody QueryRequest request, Authentication authentication) {
        rateLimiter.assertWithinLimit("ratelimit:realtime-query-execute:" + authentication.getName(), MAX_EXECUTIONS_PER_WINDOW, WINDOW);
        int limit = request.limit() == null ? 200 : request.limit();
        DataSourceEntity dataSource = requireDataSource(request.dataSourceId());
        return ApiResponse.ok(connectionService.query(dataSource, request.database(), request.sql(), limit));
    }

    private DataSourceEntity requireDataSource(Long id) {
        DataSourceEntity dataSource = dataSourceMapper.selectById(id);
        if (dataSource == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据源不存在");
        }
        return dataSource;
    }

    public record QueryRequest(@NotNull(message = "请选择数据源") Long dataSourceId, String database, @NotBlank(message = "SQL 不能为空") String sql, Integer limit) {
    }
}
