package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.DatasetAccessPolicy;
import com.company.dataops.dataservice.domain.DatasetColumnPolicy;
import com.company.dataops.dataservice.domain.DatasetRecord;
import com.company.dataops.dataservice.domain.DataSourceRecord;
import com.company.dataops.dataservice.repository.DatasetAccessPolicyRepository;
import com.company.dataops.dataservice.repository.DatasetRepository;
import com.company.dataops.dataservice.service.ManagedDataSourceService;
import com.company.dataops.dataservice.service.ChangeApprovalService;
import com.company.dataops.dataservice.service.SqlSecurityPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
@RequestMapping("/data-service-admin/datasets")
public class DatasetController {
    private final DatasetRepository repository;
    private final DatasetAccessPolicyRepository accessPolicyRepository;
    private final ManagedDataSourceService dataSourceService;
    private final SqlSecurityPolicy sqlSecurityPolicy;
    private final ChangeApprovalService changeApprovalService;
    private final ObjectMapper objectMapper;

    public DatasetController(
        DatasetRepository repository,
        DatasetAccessPolicyRepository accessPolicyRepository,
        ManagedDataSourceService dataSourceService,
        SqlSecurityPolicy sqlSecurityPolicy,
        ChangeApprovalService changeApprovalService,
        ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.accessPolicyRepository = accessPolicyRepository;
        this.dataSourceService = dataSourceService;
        this.sqlSecurityPolicy = sqlSecurityPolicy;
        this.changeApprovalService = changeApprovalService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void registerPolicyChangeExecutor() {
        changeApprovalService.register("DATASET_POLICY_UPDATE", (request, approver) -> {
            try {
                SavePolicyRequest payload = objectMapper.readValue(
                    request.payloadJson(),
                    SavePolicyRequest.class
                );
                accessPolicyRepository.replace(
                    request.targetId(),
                    payload.rowFilterSql(),
                    payload.columns() == null ? List.of() : payload.columns(),
                    approver
                );
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Invalid approved dataset policy payload", exception);
            }
        });
    }

    @GetMapping
    public ApiResponse<List<DatasetRecord>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @GetMapping("/{id}/policy")
    public ApiResponse<DatasetAccessPolicy> policy(@PathVariable long id) {
        requireDataset(id);
        return ApiResponse.ok(accessPolicyRepository.findByDatasetId(id));
    }

    @PutMapping("/{id}/policy")
    public ApiResponse<Object> updatePolicy(
        @PathVariable long id,
        @Valid @RequestBody SavePolicyRequest request,
        Authentication authentication
    ) {
        DatasetRecord dataset = requireDataset(id);
        sqlSecurityPolicy.validateRowFilter(request.rowFilterSql());
        List<DatasetColumnPolicy> columns = normalizeColumns(request.columns());
        String actor = ((AdminUserRecord) authentication.getPrincipal()).username();
        if (isProduction(dataset)) {
            SavePolicyRequest normalized = new SavePolicyRequest(request.rowFilterSql(), columns);
            try {
                return ApiResponse.ok(new PendingChangeResult(
                    true,
                    changeApprovalService.submit(
                        "DATASET_POLICY_UPDATE",
                        "DATASET",
                        id,
                        dataset.name() + " access policy",
                        "PROD",
                        objectMapper.writeValueAsString(normalized),
                        actor
                    )
                ));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to serialize dataset policy", exception);
            }
        }
        return ApiResponse.ok(accessPolicyRepository.replace(
            id,
            request.rowFilterSql(),
            columns,
            actor
        ));
    }

    private boolean isProduction(DatasetRecord dataset) {
        return dataset.connectionId() != null
            && "PROD".equalsIgnoreCase(dataSourceService.require(dataset.connectionId()).environment());
    }

    @PostMapping
    public ApiResponse<DatasetRecord> create(@Valid @RequestBody CreateDatasetRequest request) {
        String connectionMode = request.connectionId() == null ? "PLATFORM" : "MANAGED";
        String sourceType = request.sourceType().toUpperCase();
        String sourceName = request.sourceName();
        if (request.connectionId() != null) {
            DataSourceRecord source = dataSourceService.require(request.connectionId());
            if (!"ACTIVE".equals(source.status())) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "只能绑定已启用的数据源"
                );
            }
            sourceType = source.engineType();
            sourceName = source.name();
        }
        return ApiResponse.ok(repository.create(
            request.name(),
            request.description(),
            sourceType,
            sourceName,
            connectionMode,
            request.connectionId(),
            request.tableName(),
            request.owner()
        ));
    }

    public record CreateDatasetRequest(
        @NotBlank(message = "名称不能为空") String name,
        String description,
        @NotBlank(message = "来源类型不能为空") String sourceType,
        @NotBlank(message = "来源名称不能为空") String sourceName,
        String connectionMode,
        Long connectionId,
        @NotBlank(message = "表名不能为空") String tableName,
        String owner
    ) {
    }

    private DatasetRecord requireDataset(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集不存在"));
    }

    private List<DatasetColumnPolicy> normalizeColumns(List<DatasetColumnPolicy> columns) {
        List<DatasetColumnPolicy> safeColumns = columns == null ? List.of() : columns;
        Set<String> names = new HashSet<>();
        return safeColumns.stream().map(column -> {
            String name = column.columnName() == null ? "" : column.columnName().trim();
            if (!name.matches("[A-Za-z][A-Za-z0-9_$]*")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段名格式不正确：" + name);
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段策略重复：" + name);
            }
            String action = column.action() == null ? "" : column.action().toUpperCase(Locale.ROOT);
            if (!Set.of("MASK", "HIDE").contains(action)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字段动作只支持 MASK 或 HIDE");
            }
            String maskType = null;
            if ("MASK".equals(action)) {
                maskType = column.maskType() == null
                    ? "FULL"
                    : column.maskType().toUpperCase(Locale.ROOT);
                if (!Set.of("FULL", "PARTIAL", "EMAIL", "PHONE", "HASH").contains(maskType)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的脱敏方式：" + maskType);
                }
            }
            return new DatasetColumnPolicy(name, action, maskType);
        }).toList();
    }

    public record SavePolicyRequest(
        String rowFilterSql,
        List<DatasetColumnPolicy> columns
    ) {
    }

    public record PendingChangeResult(
        boolean pendingApproval,
        com.company.dataops.dataservice.domain.ChangeRequestRecord changeRequest
    ) {
    }
}
