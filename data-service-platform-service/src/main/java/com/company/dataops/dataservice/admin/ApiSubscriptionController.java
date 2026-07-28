package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.ApiSubscriptionRecord;
import com.company.dataops.dataservice.service.ApiSubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data-service-admin/subscriptions")
public class ApiSubscriptionController {
    private final ApiSubscriptionService service;

    public ApiSubscriptionController(ApiSubscriptionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ApiSubscriptionRecord>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<ApiSubscriptionRecord> submit(
        @Valid @RequestBody SubmitRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(service.submit(
            request.appId(), request.apiId(), request.reason(), request.qpsLimit(),
            request.dailyLimit(), request.validFrom(), request.validUntil(),
            request.ipAllowlist(), actor(authentication)
        ));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<ApiSubscriptionRecord> review(
        @PathVariable long id,
        @Valid @RequestBody ReviewRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(service.review(
            id, request.action(), request.qpsLimit(), request.dailyLimit(),
            request.validFrom(), request.validUntil(), request.ipAllowlist(),
            actor(authentication), request.comment()
        ));
    }

    @PostMapping("/{id}/suspend")
    public ApiResponse<ApiSubscriptionRecord> suspend(
        @PathVariable long id,
        @RequestBody(required = false) SuspendRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(service.suspend(
            id, actor(authentication), request == null ? null : request.comment()
        ));
    }

    private static String actor(Authentication authentication) {
        return ((AdminUserRecord) authentication.getPrincipal()).username();
    }

    public record SubmitRequest(
        @NotNull Long appId,
        @NotNull Long apiId,
        String reason,
        @NotNull @Min(1) @Max(10000) Integer qpsLimit,
        @NotNull @Min(1) Long dailyLimit,
        Instant validFrom,
        Instant validUntil,
        List<String> ipAllowlist
    ) {
    }

    public record ReviewRequest(
        @NotNull String action,
        @NotNull @Min(1) @Max(10000) Integer qpsLimit,
        @NotNull @Min(1) Long dailyLimit,
        Instant validFrom,
        Instant validUntil,
        List<String> ipAllowlist,
        String comment
    ) {
    }

    public record SuspendRequest(String comment) {
    }
}
