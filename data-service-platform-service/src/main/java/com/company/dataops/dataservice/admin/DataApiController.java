package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.ApiParameter;
import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.ExecutionResult;
import com.company.dataops.dataservice.repository.DataApiRepository;
import com.company.dataops.dataservice.repository.DatasetRepository;
import com.company.dataops.dataservice.service.ApiExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/data-service-admin/apis")
public class DataApiController {
    private final DataApiRepository repository;
    private final DatasetRepository datasetRepository;
    private final ApiExecutionService executionService;

    public DataApiController(
        DataApiRepository repository,
        DatasetRepository datasetRepository,
        ApiExecutionService executionService
    ) {
        this.repository = repository;
        this.datasetRepository = datasetRepository;
        this.executionService = executionService;
    }

    @GetMapping
    public ApiResponse<List<DataApiRecord>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<ApiVersionRecord>> versions(@PathVariable long id) {
        requireApi(id);
        return ApiResponse.ok(repository.findVersions(id));
    }

    @PostMapping
    public ApiResponse<DataApiRecord> create(
        @Valid @RequestBody SaveDataApiRequest request,
        Authentication authentication
    ) {
        validateDefinitionRequest(request);
        try {
            return ApiResponse.ok(repository.create(
                request.datasetId(),
                request.name().trim(),
                request.description(),
                normalizePath(request.path()),
                normalizeMethod(request.method()),
                request.querySql().trim(),
                safeParameters(request.parameters()),
                request.cacheTtlSeconds(),
                request.maxPageSize() == null ? 100 : request.maxPageSize(),
                actor(authentication),
                request.changeSummary() == null || request.changeSummary().isBlank()
                    ? "创建 API"
                    : request.changeSummary().trim()
            ));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "相同路径和方法的 API 已存在");
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<DataApiRecord> update(
        @PathVariable long id,
        @Valid @RequestBody SaveDataApiRequest request,
        Authentication authentication
    ) {
        requireApi(id);
        validateDefinitionRequest(request);
        if (request.changeSummary() == null || request.changeSummary().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "修改 API 时必须填写变更说明");
        }
        try {
            return ApiResponse.ok(repository.update(
                id,
                request.datasetId(),
                request.name().trim(),
                request.description(),
                normalizePath(request.path()),
                normalizeMethod(request.method()),
                request.querySql().trim(),
                safeParameters(request.parameters()),
                request.cacheTtlSeconds(),
                request.maxPageSize() == null ? 100 : request.maxPageSize(),
                actor(authentication),
                request.changeSummary().trim()
            ));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "相同路径和方法的 API 已存在");
        }
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<ApiVersionRecord> submit(
        @PathVariable long id,
        Authentication authentication
    ) {
        DataApiRecord api = requireApi(id);
        executionService.validateDefinition(api);
        return ApiResponse.ok(repository.submitForApproval(id, actor(authentication)));
    }

    @PostMapping("/{id}/versions/{versionNo}/review")
    public ApiResponse<?> review(
        @PathVariable long id,
        @PathVariable int versionNo,
        @Valid @RequestBody ReviewRequest request,
        Authentication authentication
    ) {
        DataApiRecord api = requireApi(id);
        ApiVersionRecord version = repository.findVersion(id, versionNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API 版本不存在"));
        String action = request.action().toUpperCase(Locale.ROOT);
        if ("APPROVE".equals(action)) {
            executionService.validateDefinition(version.asApi(api));
            return ApiResponse.ok(repository.approve(
                id, versionNo, actor(authentication), request.comment()
            ));
        }
        if ("REJECT".equals(action)) {
            if (request.comment() == null || request.comment().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回时必须填写原因");
            }
            return ApiResponse.ok(repository.reject(
                id, versionNo, actor(authentication), request.comment().trim()
            ));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批动作只支持 APPROVE 或 REJECT");
    }

    @PostMapping("/{id}/versions/{versionNo}/rollback")
    public ApiResponse<DataApiRecord> rollback(
        @PathVariable long id,
        @PathVariable int versionNo,
        @Valid @RequestBody RollbackRequest request,
        Authentication authentication
    ) {
        DataApiRecord api = requireApi(id);
        ApiVersionRecord source = repository.findVersion(id, versionNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "历史版本不存在"));
        executionService.validateDefinition(source.asApi(api));
        return ApiResponse.ok(repository.rollback(
            id,
            versionNo,
            actor(authentication),
            request.changeSummary().trim()
        ));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<DataApiRecord> changeStatus(
        @PathVariable long id,
        @Valid @RequestBody ChangeStatusRequest request
    ) {
        requireApi(id);
        if (!"OFFLINE".equalsIgnoreCase(request.action())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发布必须提交审批，状态接口只支持 OFFLINE");
        }
        return ApiResponse.ok(repository.offline(id));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ExecutionResult> test(
        @PathVariable long id,
        @RequestBody(required = false) TestApiRequest request,
        HttpServletRequest servletRequest
    ) {
        DataApiRecord api = requireApi(id);
        TestApiRequest safeRequest = request == null ? new TestApiRequest(Map.of(), 1, 20) : request;
        return ApiResponse.ok(executionService.execute(
            api,
            safeRequest.parameters(),
            safeRequest.page(),
            safeRequest.pageSize(),
            "admin-console",
            clientIp(servletRequest),
            true
        ));
    }

    private void validateDefinitionRequest(SaveDataApiRequest request) {
        if (datasetRepository.findById(request.datasetId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据集不存在");
        }
        normalizePath(request.path());
        String method = normalizeMethod(request.method());
        if (!List.of("GET", "POST").contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求方法只支持 GET 或 POST");
        }
        executionService.validateDefinition(
            request.datasetId(),
            request.querySql(),
            safeParameters(request.parameters())
        );
    }

    private DataApiRecord requireApi(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API 不存在"));
    }

    private String normalizePath(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (!normalized.matches("/[A-Za-z0-9/_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API 路径格式不正确");
        }
        return normalized;
    }

    private String normalizeMethod(String method) {
        return method == null ? "GET" : method.toUpperCase(Locale.ROOT);
    }

    private List<ApiParameter> safeParameters(List<ApiParameter> parameters) {
        return parameters == null ? List.of() : parameters;
    }

    private String actor(Authentication authentication) {
        return ((AdminUserRecord) authentication.getPrincipal()).username();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",")[0].trim();
    }

    public record SaveDataApiRequest(
        @NotNull(message = "数据集不能为空") Long datasetId,
        @NotBlank(message = "名称不能为空") String name,
        String description,
        @NotBlank(message = "路径不能为空") String path,
        String method,
        @NotBlank(message = "查询 SQL 不能为空") String querySql,
        List<ApiParameter> parameters,
        @Min(0) Integer cacheTtlSeconds,
        @Min(1) @Max(500) Integer maxPageSize,
        String changeSummary
    ) {
    }

    public record ReviewRequest(
        @NotBlank(message = "审批动作不能为空") String action,
        String comment
    ) {
    }

    public record RollbackRequest(
        @NotBlank(message = "回滚说明不能为空") String changeSummary
    ) {
    }

    public record ChangeStatusRequest(@NotBlank String action) {
    }

    public record TestApiRequest(Map<String, Object> parameters, Integer page, Integer pageSize) {
    }
}
