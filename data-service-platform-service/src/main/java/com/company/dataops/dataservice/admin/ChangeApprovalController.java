package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.ChangeRequestRecord;
import com.company.dataops.dataservice.service.ChangeApprovalService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data-service-admin/change-requests")
public class ChangeApprovalController {
    private final ChangeApprovalService service;

    public ChangeApprovalController(ChangeApprovalService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ChangeRequestRecord>> list(
        @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(service.list(limit));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ChangeRequestRecord> approve(
        @PathVariable long id,
        @RequestBody(required = false) DecisionRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(service.approve(id, actor(authentication), comment(request)));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ChangeRequestRecord> reject(
        @PathVariable long id,
        @RequestBody(required = false) DecisionRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(service.reject(id, actor(authentication), comment(request)));
    }

    private static String actor(Authentication authentication) {
        return ((AdminUserRecord) authentication.getPrincipal()).username();
    }

    private static String comment(DecisionRequest request) {
        return request == null ? null : request.comment();
    }

    public record DecisionRequest(String comment) {
    }
}
