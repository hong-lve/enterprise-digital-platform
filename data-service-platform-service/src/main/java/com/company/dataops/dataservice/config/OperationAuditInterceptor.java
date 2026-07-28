package com.company.dataops.dataservice.config;

import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.service.OperationAuditService;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class OperationAuditInterceptor implements HandlerInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(OperationAuditInterceptor.class);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern RESOURCE_ID = Pattern.compile("/(\\d+)(?:/|$)");

    private final OperationAuditService auditService;
    private final Tracer tracer;

    public OperationAuditInterceptor(OperationAuditService auditService, Tracer tracer) {
        this.auditService = auditService;
        this.tracer = tracer;
    }

    @Override
    public void afterCompletion(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler,
        Exception exception
    ) {
        if (!MUTATING_METHODS.contains(request.getMethod())
            || request.getRequestURI().startsWith("/data-service-admin/auth/")) {
            return;
        }
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String actor = authentication != null && authentication.getPrincipal() instanceof AdminUserRecord user
                ? user.username()
                : null;
            String operation = handler instanceof HandlerMethod method
                ? method.getBeanType().getSimpleName() + "." + method.getMethod().getName()
                : null;
            String traceId = tracer.currentSpan() == null
                ? null
                : tracer.currentSpan().context().traceId();
            auditService.record(
                actor,
                clientIp(request),
                traceId,
                request.getMethod(),
                request.getRequestURI(),
                operation,
                resourceId(request.getRequestURI()),
                response.getStatus(),
                exception == null ? null : exception.getMessage()
            );
        } catch (RuntimeException auditFailure) {
            LOGGER.warn("Operation audit recording failed for {}", request.getRequestURI(), auditFailure);
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",", 2)[0].trim();
    }

    private static String resourceId(String path) {
        Matcher matcher = RESOURCE_ID.matcher(path);
        String result = null;
        while (matcher.find()) {
            result = matcher.group(1);
        }
        return result;
    }
}
