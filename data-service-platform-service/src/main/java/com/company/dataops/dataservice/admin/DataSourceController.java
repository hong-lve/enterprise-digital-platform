package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.DataSourceRecord;
import com.company.dataops.dataservice.repository.DataSourceRepository;
import com.company.dataops.dataservice.security.SecretCryptoService;
import com.company.dataops.dataservice.service.ManagedDataSourceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/data-service-admin/data-sources")
public class DataSourceController {
    private static final Set<String> ENGINES = Set.of("MYSQL", "ORACLE", "DORIS", "CLICKHOUSE");

    private final DataSourceRepository repository;
    private final ManagedDataSourceService service;
    private final SecretCryptoService cryptoService;

    public DataSourceController(
        DataSourceRepository repository,
        ManagedDataSourceService service,
        SecretCryptoService cryptoService
    ) {
        this.repository = repository;
        this.service = service;
        this.cryptoService = cryptoService;
    }

    @GetMapping
    public ApiResponse<List<DataSourceRecord>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @PostMapping
    public ApiResponse<DataSourceRecord> create(@Valid @RequestBody SaveDataSourceRequest request) {
        validate(request);
        if (request.password() == null || request.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不能为空");
        }
        return ApiResponse.ok(repository.create(toRecord(null, request, cryptoService.encrypt(request.password()))));
    }

    @PutMapping("/{id}")
    public ApiResponse<DataSourceRecord> update(
        @PathVariable long id,
        @Valid @RequestBody SaveDataSourceRequest request
    ) {
        DataSourceRecord existing = service.require(id);
        validate(request);
        String encryptedPassword = request.password() == null || request.password().isBlank()
            ? null
            : cryptoService.encrypt(request.password());
        service.evict(id);
        return ApiResponse.ok(repository.update(id, toRecord(existing, request, existing.passwordCiphertext()), encryptedPassword));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<DataSourceRecord> test(@PathVariable long id) {
        return ApiResponse.ok(service.test(id));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<DataSourceRecord> status(@PathVariable long id, @RequestBody StatusRequest request) {
        DataSourceRecord source = service.require(id);
        String action = request.action() == null ? "" : request.action().toUpperCase(Locale.ROOT);
        if ("ENABLE".equals(action) && !"SUCCESS".equals(source.lastTestStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "数据源连接测试成功后才能启用");
        }
        String status = switch (action) {
            case "ENABLE" -> "ACTIVE";
            case "DISABLE" -> "DISABLED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态动作只支持 ENABLE 或 DISABLE");
        };
        service.evict(id);
        return ApiResponse.ok(repository.updateStatus(id, status));
    }

    private void validate(SaveDataSourceRequest request) {
        String engine = request.engineType().toUpperCase(Locale.ROOT);
        if (!ENGINES.contains(engine)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的数据源类型：" + request.engineType());
        }
        if (request.poolMinIdle() > request.poolMaxSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最小空闲连接数不能大于最大连接数");
        }
    }

    private DataSourceRecord toRecord(DataSourceRecord existing, SaveDataSourceRequest request, String encryptedPassword) {
        return new DataSourceRecord(
            existing == null ? null : existing.id(),
            request.name().trim(),
            request.engineType().toUpperCase(Locale.ROOT),
            request.host().trim(),
            request.port(),
            request.databaseName().trim(),
            request.username().trim(),
            encryptedPassword,
            request.poolMinIdle(),
            request.poolMaxSize(),
            request.connectionTimeoutMs(),
            request.queryTimeoutSeconds(),
            request.environment().toUpperCase(Locale.ROOT),
            request.owner(),
            existing == null ? "DRAFT" : existing.status(),
            null,
            null,
            null,
            existing == null ? null : existing.createdAt(),
            existing == null ? null : existing.updatedAt()
        );
    }

    public record SaveDataSourceRequest(
        @NotBlank(message = "名称不能为空") String name,
        @NotBlank(message = "引擎类型不能为空") String engineType,
        @NotBlank(message = "主机地址不能为空") String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotBlank(message = "数据库或服务名不能为空") String databaseName,
        @NotBlank(message = "用户名不能为空") String username,
        String password,
        @NotNull @Min(0) @Max(50) Integer poolMinIdle,
        @NotNull @Min(1) @Max(100) Integer poolMaxSize,
        @NotNull @Min(1000) @Max(60000) Long connectionTimeoutMs,
        @NotNull @Min(1) @Max(300) Integer queryTimeoutSeconds,
        @NotBlank(message = "环境不能为空") String environment,
        String owner
    ) {
    }

    public record StatusRequest(String action) {
    }
}
