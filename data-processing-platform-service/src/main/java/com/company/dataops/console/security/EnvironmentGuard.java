package com.company.dataops.console.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared PROD-tag gate for CdcSourceController and FlinkStreamJobController
 * - avoids repeating the SecurityContextHolder lookup in every start/stop/
 * delete/update method on both controllers. A no-op for anything not
 * tagged PROD, so callers can call it unconditionally.
 */
@Component
public class EnvironmentGuard {
    private static final String PROD_OPERATE_PERMISSION = "realtime:env:prod-operate";

    public void requirePermissionForEnvironment(String environment) {
        if (!"PROD".equals(environment)) {
            return;
        }
        boolean hasPermission = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .anyMatch(authority -> PROD_OPERATE_PERMISSION.equals(authority.getAuthority()));
        if (!hasPermission) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该记录属于生产环境，需要生产环境操作权限（" + PROD_OPERATE_PERMISSION + "）");
        }
    }
}
