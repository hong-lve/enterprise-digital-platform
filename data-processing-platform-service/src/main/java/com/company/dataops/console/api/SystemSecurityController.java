package com.company.dataops.console.api;

import com.company.dataops.console.common.ActionResult;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.security.TwoFactorSettingService;
import com.company.dataops.console.service.approval.ChangeApprovalService;
import com.company.dataops.console.service.security.EncryptionKeyRotationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global TOTP two-factor toggle (系统管理/安全设置) - takes effect immediately
 * for every subsequent login, no redeploy or restart needed (see
 * TwoFactorSettingService, read fresh on every /auth/login) - plus
 * encryption-key rotation status/action (see EncryptionKeyRotationService),
 * both under the same page/permission since both are global security
 * settings, not tied to any one entity type.
 */
@RestController
@RequestMapping("/system/security")
public class SystemSecurityController {
    // Sentinel - rotation has no single target entity, but change_request.target_id is NOT NULL.
    private static final long ROTATION_TARGET_ID = 0L;

    private final TwoFactorSettingService twoFactorSettingService;
    private final EncryptionKeyRotationService encryptionKeyRotationService;
    private final ChangeApprovalService changeApprovalService;

    public SystemSecurityController(
        TwoFactorSettingService twoFactorSettingService,
        EncryptionKeyRotationService encryptionKeyRotationService,
        ChangeApprovalService changeApprovalService
    ) {
        this.twoFactorSettingService = twoFactorSettingService;
        this.encryptionKeyRotationService = encryptionKeyRotationService;
        this.changeApprovalService = changeApprovalService;
        // Always-gated like ROLE_PERMISSION_UPDATE/USER_DISABLE - re-encrypting
        // every stored secret in the system is sensitive regardless of any
        // DEV/PROD tag, since there's no environment concept for a global key.
        changeApprovalService.registerWithPayload(ChangeApprovalService.ActionType.ENCRYPTION_KEY_ROTATE,
            (targetId, payload) -> encryptionKeyRotationService.rotate(payload));
    }

    @GetMapping("/two-factor")
    @PreAuthorize("hasAuthority('system:security:view')")
    public ApiResponse<TwoFactorSettingView> twoFactorSetting() {
        return ApiResponse.ok(new TwoFactorSettingView(twoFactorSettingService.isEnabled()));
    }

    @PutMapping("/two-factor")
    @PreAuthorize("hasAuthority('system:security:update')")
    public ApiResponse<TwoFactorSettingView> updateTwoFactorSetting(@RequestBody UpdateTwoFactorSettingRequest request) {
        twoFactorSettingService.setEnabled(request.enabled());
        return ApiResponse.ok(new TwoFactorSettingView(request.enabled()));
    }

    @GetMapping("/encryption-keys")
    @PreAuthorize("hasAuthority('system:security:view')")
    public ApiResponse<EncryptionKeyRotationService.StatusView> encryptionKeyStatus() {
        return ApiResponse.ok(encryptionKeyRotationService.status());
    }

    @PostMapping("/encryption-keys/rotate")
    @PreAuthorize("hasAuthority('system:security:update')")
    public ApiResponse<ActionResult> rotateEncryptionKeys() {
        String requester = SecurityContextHolder.getContext().getAuthentication().getName();
        ChangeApprovalService.GateResult gate = changeApprovalService.gateAlwaysWithPayload(
            ChangeApprovalService.ActionType.ENCRYPTION_KEY_ROTATE, ROTATION_TARGET_ID, requester, "轮换加密密钥");
        if (gate.pending()) {
            return ApiResponse.ok(ActionResult.pending(gate.requestId()));
        }
        return ApiResponse.ok(ActionResult.applied());
    }

    public record TwoFactorSettingView(boolean enabled) {
    }

    public record UpdateTwoFactorSettingRequest(boolean enabled) {
    }
}
