package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.NotificationChannelRecord;
import com.company.dataops.dataservice.domain.NotificationDeliveryRecord;
import com.company.dataops.dataservice.domain.OperationAuditRecord;
import com.company.dataops.dataservice.repository.GovernanceRepository;
import com.company.dataops.dataservice.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data-service-admin/governance")
public class GovernanceController {
    private final NotificationService notificationService;
    private final GovernanceRepository repository;

    public GovernanceController(
        NotificationService notificationService,
        GovernanceRepository repository
    ) {
        this.notificationService = notificationService;
        this.repository = repository;
    }

    @GetMapping("/channels")
    public ApiResponse<List<NotificationChannelRecord>> channels() {
        return ApiResponse.ok(notificationService.channels());
    }

    @PostMapping("/channels")
    public ApiResponse<NotificationChannelRecord> createChannel(
        @Valid @RequestBody SaveChannelRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(notificationService.create(
            request.name(),
            request.channelType(),
            request.endpoint(),
            request.enabled(),
            actor(authentication)
        ));
    }

    @PutMapping("/channels/{id}")
    public ApiResponse<NotificationChannelRecord> updateChannel(
        @PathVariable long id,
        @Valid @RequestBody SaveChannelRequest request
    ) {
        return ApiResponse.ok(notificationService.update(
            id,
            request.name(),
            request.channelType(),
            request.endpoint(),
            request.enabled()
        ));
    }

    @PostMapping("/channels/{id}/test")
    public ApiResponse<Void> testChannel(@PathVariable long id) {
        notificationService.enqueueTest(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<NotificationDeliveryRecord>> deliveries(
        @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(notificationService.deliveries(limit));
    }

    @GetMapping("/audits")
    public ApiResponse<List<OperationAuditRecord>> audits(
        @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(repository.findAudits(Math.min(Math.max(limit, 1), 500)));
    }

    @GetMapping("/audits/integrity")
    public ApiResponse<GovernanceRepository.AuditIntegrity> auditIntegrity() {
        return ApiResponse.ok(repository.verifyAuditIntegrity());
    }

    private static String actor(Authentication authentication) {
        return ((AdminUserRecord) authentication.getPrincipal()).username();
    }

    public record SaveChannelRequest(
        @NotBlank String name,
        @NotBlank String channelType,
        String endpoint,
        boolean enabled
    ) {
    }
}
