package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.security.TwoFactorSettingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global TOTP two-factor toggle (系统管理/安全设置) - takes effect immediately
 * for every subsequent login, no redeploy or restart needed (see
 * TwoFactorSettingService, read fresh on every /auth/login).
 */
@RestController
@RequestMapping("/system/security")
public class SystemSecurityController {
    private final TwoFactorSettingService twoFactorSettingService;

    public SystemSecurityController(TwoFactorSettingService twoFactorSettingService) {
        this.twoFactorSettingService = twoFactorSettingService;
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

    public record TwoFactorSettingView(boolean enabled) {
    }

    public record UpdateTwoFactorSettingRequest(boolean enabled) {
    }
}
