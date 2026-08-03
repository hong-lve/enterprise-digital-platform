package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.ApiRolloutDetail;
import com.company.dataops.dataservice.domain.ApiRolloutRecord;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.RolloutHealthPolicy;
import com.company.dataops.dataservice.domain.RolloutStage;
import com.company.dataops.dataservice.service.ApiRolloutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data-service-admin/rollouts")
public class ApiRolloutController {
    private final ApiRolloutService rolloutService;

    public ApiRolloutController(ApiRolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    @GetMapping("/apis/{apiId}")
    public ApiResponse<ApiRolloutDetail> detail(@PathVariable long apiId) {
        return ApiResponse.ok(rolloutService.detail(apiId));
    }

    @PostMapping("/apis/{apiId}")
    public ApiResponse<ApiRolloutRecord> start(
        @PathVariable long apiId,
        @Valid @RequestBody SaveRolloutRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(rolloutService.start(
            apiId,
            request.candidateVersionNo(),
            request.percentage(),
            request.applicationIds(),
            request.ipRules(),
            request.note(),
            actor(authentication),
            request.stages(),
            request.healthPolicy(),
            request.failureAction()
        ));
    }

    @PutMapping("/{rolloutId}")
    public ApiResponse<ApiRolloutRecord> update(
        @PathVariable long rolloutId,
        @Valid @RequestBody UpdateRolloutRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(rolloutService.update(
            rolloutId,
            request.percentage(),
            request.applicationIds(),
            request.ipRules(),
            request.note(),
            actor(authentication)
        ));
    }

    @PostMapping("/{rolloutId}/promote")
    public ApiResponse<DataApiRecord> promote(
        @PathVariable long rolloutId,
        Authentication authentication
    ) {
        return ApiResponse.ok(rolloutService.promote(rolloutId, actor(authentication)));
    }

    @PostMapping("/{rolloutId}/rollback")
    public ApiResponse<ApiRolloutRecord> rollback(
        @PathVariable long rolloutId,
        Authentication authentication
    ) {
        return ApiResponse.ok(rolloutService.rollback(rolloutId, actor(authentication)));
    }

    @PostMapping("/{rolloutId}/pause")
    public ApiResponse<ApiRolloutRecord> pause(
        @PathVariable long rolloutId,
        @RequestBody(required = false) PauseRolloutRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(rolloutService.pause(
            rolloutId,
            request == null ? null : request.reason(),
            actor(authentication)
        ));
    }

    @PostMapping("/{rolloutId}/resume")
    public ApiResponse<ApiRolloutRecord> resume(
        @PathVariable long rolloutId,
        Authentication authentication
    ) {
        return ApiResponse.ok(rolloutService.resume(rolloutId, actor(authentication)));
    }

    private String actor(Authentication authentication) {
        return ((AdminUserRecord) authentication.getPrincipal()).username();
    }

    public record SaveRolloutRequest(
        @NotNull Integer candidateVersionNo,
        @NotNull @Min(0) @Max(99) Integer percentage,
        Set<Long> applicationIds,
        List<String> ipRules,
        String note,
        List<RolloutStage> stages,
        RolloutHealthPolicy healthPolicy,
        String failureAction
    ) {
    }

    public record UpdateRolloutRequest(
        @NotNull @Min(0) @Max(99) Integer percentage,
        Set<Long> applicationIds,
        List<String> ipRules,
        String note
    ) {
    }

    public record PauseRolloutRequest(String reason) {
    }
}
