package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.service.datasource.DataSourceConnectionService;
import com.company.dataops.console.service.datasource.RedisConnectionService;
import com.company.dataops.console.service.query.ColumnView;
import com.company.dataops.console.service.query.QueryResult;
import com.company.dataops.console.service.query.RedisKeyView;
import com.company.dataops.console.service.query.TableView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final DataSourceMapper dataSourceMapper;
    private final DataSourceConnectionService connectionService;
    private final RedisConnectionService redisConnectionService;

    public RealtimeQueryController(DataSourceMapper dataSourceMapper, DataSourceConnectionService connectionService, RedisConnectionService redisConnectionService) {
        this.dataSourceMapper = dataSourceMapper;
        this.connectionService = connectionService;
        this.redisConnectionService = redisConnectionService;
    }

    @GetMapping("/tables")
    @PreAuthorize("hasAuthority('realtime:query:execute')")
    public ApiResponse<List<TableView>> tables(@RequestParam Long dataSourceId, @RequestParam(required = false) String database) {
        DataSourceEntity dataSource = requireDataSource(dataSourceId);
        // Redis has no table concept - the frontend skips this panel entirely
        // for Redis data sources and goes straight to the command box, but
        // returning an empty list rather than throwing keeps this endpoint
        // harmless if it's ever called for one anyway.
        if (isRedis(dataSource)) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(connectionService.tables(dataSource, database));
    }

    @GetMapping("/tables/{table}/columns")
    @PreAuthorize("hasAuthority('realtime:query:execute')")
    public ApiResponse<List<ColumnView>> columns(@PathVariable String table, @RequestParam Long dataSourceId, @RequestParam(required = false) String database) {
        DataSourceEntity dataSource = requireDataSource(dataSourceId);
        if (isRedis(dataSource)) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(connectionService.columns(dataSource, database, table));
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAuthority('realtime:query:execute')")
    public ApiResponse<QueryResult> execute(@Valid @RequestBody QueryRequest request) {
        int limit = request.limit() == null ? 200 : request.limit();
        DataSourceEntity dataSource = requireDataSource(request.dataSourceId());
        // request.sql() doubles as "the Redis command line" when the data
        // source is REDIS - same request shape, no separate endpoint/DTO,
        // mirrors DebeziumConnectorConfigBuilder's dispatch-by-type style.
        if (isRedis(dataSource)) {
            return ApiResponse.ok(redisConnectionService.execute(dataSource, request.database(), request.sql(), limit));
        }
        return ApiResponse.ok(connectionService.query(dataSource, request.database(), request.sql(), limit));
    }

    @GetMapping("/redis/keys")
    @PreAuthorize("hasAuthority('realtime:query:execute')")
    public ApiResponse<List<RedisKeyView>> redisKeys(@RequestParam Long dataSourceId, @RequestParam(required = false) String database, @RequestParam(required = false) String pattern) {
        DataSourceEntity dataSource = requireDataSource(dataSourceId);
        requireRedis(dataSource);
        return ApiResponse.ok(redisConnectionService.listKeysWithMeta(dataSource, database, pattern, 200));
    }

    @GetMapping("/redis/value")
    @PreAuthorize("hasAuthority('realtime:query:execute')")
    public ApiResponse<QueryResult> redisValue(@RequestParam Long dataSourceId, @RequestParam(required = false) String database, @RequestParam String key) {
        DataSourceEntity dataSource = requireDataSource(dataSourceId);
        requireRedis(dataSource);
        return ApiResponse.ok(redisConnectionService.getValue(dataSource, database, key));
    }

    private void requireRedis(DataSourceEntity dataSource) {
        if (!isRedis(dataSource)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该数据源不是 Redis 类型");
        }
    }

    private boolean isRedis(DataSourceEntity dataSource) {
        return "REDIS".equalsIgnoreCase(dataSource.getType());
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
