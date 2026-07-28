package com.company.dataops.console.config;

import com.company.dataops.console.entity.AuditLogEntity;
import com.company.dataops.console.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Records who hit which mutating endpoint, when, and whether it succeeded -
 * sys_login_log only ever covered login attempts, leaving every other
 * config change (data sources, roles, menus, CDC sources, JAR uploads, ...)
 * with no trail beyond an entity's own updated_at column (says "something
 * changed", never "who" or "how"). A cross-cutting interceptor instead of a
 * manual log call added to every mutating controller method - the
 * alternative means a call this codebase's many controllers would
 * inevitably miss on some future endpoint.
 *
 * Deliberately does NOT log the request body: several of those bodies
 * carry secrets outright (DataSourceEntity.password, the TOTP secret in
 * AuthController's setup response, sys_user's password on reset) - a
 * generic body-dump here would put a plaintext credential in a log table on
 * every one of this platform's several password-bearing endpoints. What
 * gets recorded (who, which endpoint, when, success/failure) is real
 * accountability even without a field-level diff of what changed - the
 * entity's own current row (or, for a delete, whichever alert/history
 * table already exists for that entity type) is where a diff would come
 * from if one were ever needed.
 */
@Component
public class AuditLogInterceptor implements HandlerInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditLogInterceptor.class);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final AuditLogMapper auditLogMapper;

    public AuditLogInterceptor(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            return;
        }
        // /auth/** (login/logout/2fa) already has its own dedicated
        // sys_login_log trail (AuthController.saveLoginLog) with details
        // this generic interceptor can't safely capture anyway (a login
        // attempt's own request body is a plaintext password) - logging it
        // again here would be redundant at best.
        String path = request.getRequestURI();
        if (path == null || path.contains("/auth/")) {
            return;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return;
        }
        try {
            AuditLogEntity log = new AuditLogEntity();
            log.setUsername(currentUsername());
            log.setIpAddress(resolveIpAddress(request));
            log.setHttpMethod(request.getMethod());
            log.setPath(path);
            log.setPermission(extractPermission(handlerMethod));
            boolean failed = ex != null || response.getStatus() >= 400;
            log.setStatus(failed ? "FAILURE" : "SUCCESS");
            log.setErrorMessage(ex != null ? truncate(ex.getMessage()) : (failed ? "HTTP " + response.getStatus() : null));
            log.setOccurredAt(LocalDateTime.now());
            auditLogMapper.insert(log);
        } catch (Exception loggingFailure) {
            // Must never break the actual response just because the audit
            // trail itself couldn't be written - same "best-effort,
            // swallow and log" posture RealtimeAlertService already uses
            // for its own history-recording side effect.
            LOGGER.warn("Failed to record audit log entry for {} {}: {}", request.getMethod(), path, loggingFailure.getMessage());
        }
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        return authentication.getName();
    }

    private String extractPermission(HandlerMethod handlerMethod) {
        PreAuthorize preAuthorize = handlerMethod.getMethodAnnotation(PreAuthorize.class);
        if (preAuthorize == null) {
            return null;
        }
        // Value is a SpEL expression, almost always exactly
        // hasAuthority('some:permission:string') across this codebase's
        // controllers - extracting the literal between quotes covers that
        // shape without needing a real SpEL parser for what's meant to be
        // a human-readable audit column, not an executable expression.
        String expression = preAuthorize.value();
        int start = expression.indexOf('\'');
        int end = expression.lastIndexOf('\'');
        return start >= 0 && end > start ? expression.substring(start + 1, end) : expression;
    }

    private String resolveIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) : message;
    }
}
