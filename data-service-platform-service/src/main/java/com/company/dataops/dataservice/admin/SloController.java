package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.AlertEventRecord;
import com.company.dataops.dataservice.domain.SloRuleRecord;
import com.company.dataops.dataservice.repository.DataApiRepository;
import com.company.dataops.dataservice.repository.SloRepository;
import com.company.dataops.dataservice.service.SloEvaluationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/data-service-admin/slo")
public class SloController {
    private final SloRepository repository;
    private final DataApiRepository apiRepository;
    private final SloEvaluationService evaluationService;

    public SloController(
        SloRepository repository,
        DataApiRepository apiRepository,
        SloEvaluationService evaluationService
    ) {
        this.repository = repository;
        this.apiRepository = apiRepository;
        this.evaluationService = evaluationService;
    }

    @GetMapping("/rules")
    public ApiResponse<List<SloRuleRecord>> rules() {
        return ApiResponse.ok(repository.findRules());
    }

    @PostMapping("/rules")
    public ApiResponse<SloRuleRecord> create(
        @Valid @RequestBody SaveSloRuleRequest request,
        Authentication authentication
    ) {
        return save(null, request, actor(authentication));
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<SloRuleRecord> update(
        @PathVariable long id,
        @Valid @RequestBody SaveSloRuleRequest request,
        Authentication authentication
    ) {
        repository.findRule(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SLO 规则不存在"));
        return save(id, request, actor(authentication));
    }

    @PostMapping("/rules/{id}/evaluate")
    public ApiResponse<SloEvaluationService.EvaluationResult> evaluate(@PathVariable long id) {
        repository.findRule(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SLO 规则不存在"));
        return ApiResponse.ok(evaluationService.evaluate(id));
    }

    @GetMapping("/alerts")
    public ApiResponse<List<AlertEventRecord>> alerts(
        @RequestParam(defaultValue = "200") int limit
    ) {
        return ApiResponse.ok(repository.findAlerts(Math.max(1, Math.min(limit, 500))));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ApiResponse<AlertEventRecord> acknowledge(
        @PathVariable long id,
        Authentication authentication
    ) {
        requireAlert(id);
        return ApiResponse.ok(repository.acknowledge(id, actor(authentication)));
    }

    @PostMapping("/alerts/{id}/resolve")
    public ApiResponse<AlertEventRecord> resolve(@PathVariable long id) {
        requireAlert(id);
        return ApiResponse.ok(repository.resolve(id));
    }

    private ApiResponse<SloRuleRecord> save(
        Long id,
        SaveSloRuleRequest request,
        String actor
    ) {
        if (apiRepository.findById(request.apiId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API 不存在");
        }
        try {
            return ApiResponse.ok(repository.save(
                id,
                request.apiId(),
                request.name().trim(),
                request.enabled(),
                request.windowMinutes(),
                request.minRequests(),
                request.minSuccessRate(),
                request.maxP95Ms(),
                actor
            ));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该 API 已配置 SLO 规则");
        }
    }

    private AlertEventRecord requireAlert(long id) {
        return repository.findAlert(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在"));
    }

    private String actor(Authentication authentication) {
        return ((AdminUserRecord) authentication.getPrincipal()).username();
    }

    public record SaveSloRuleRequest(
        @NotNull Long apiId,
        @NotBlank String name,
        boolean enabled,
        @Min(1) @Max(1440) int windowMinutes,
        @Min(1) @Max(1000000) int minRequests,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal minSuccessRate,
        @Min(1) long maxP95Ms
    ) {
    }
}
