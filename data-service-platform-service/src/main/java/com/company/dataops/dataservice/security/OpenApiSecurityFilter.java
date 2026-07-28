package com.company.dataops.dataservice.security;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.repository.CallLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class OpenApiSecurityFilter extends OncePerRequestFilter {
    public static final String ATTR_APP_ID = OpenApiSecurityFilter.class.getName() + ".appId";
    public static final String ATTR_APP_KEY = OpenApiSecurityFilter.class.getName() + ".appKey";

    private final OpenApiSecurityService securityService;
    private final CallLogRepository callLogRepository;
    private final ObjectMapper objectMapper;

    public OpenApiSecurityFilter(
        OpenApiSecurityService securityService,
        CallLogRepository callLogRepository,
        ObjectMapper objectMapper
    ) {
        this.securityService = securityService;
        this.callLogRepository = callLogRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/openapi/") || "/openapi/health".equals(path);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, body);
        String appKey = request.getHeader("X-App-Key");
        try {
            OpenApiSecurityService.AuthenticationResult result = securityService.authenticate(
                new OpenApiSecurityService.SignedRequest(
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getQueryString(),
                    request.getHeader("X-Timestamp"),
                    request.getHeader("X-Nonce"),
                    OpenApiSecurityService.sha256Hex(body),
                    appKey,
                    request.getHeader("X-Signature"),
                    request.getHeader("X-Secret-Version")
                )
            );
            cachedRequest.setAttribute(ATTR_APP_ID, result.appId());
            cachedRequest.setAttribute(ATTR_APP_KEY, result.appKey());
            response.setHeader("X-RateLimit-Limit", String.valueOf(result.qpsLimit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
            response.setHeader("X-Secret-Version", String.valueOf(result.secretVersion()));
            filterChain.doFilter(cachedRequest, response);
        } catch (GatewaySecurityException exception) {
            auditRejection(request, appKey, exception);
            response.setStatus(exception.status().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(exception.status().value(), exception.getMessage())
            );
        }
    }

    private void auditRejection(
        HttpServletRequest request,
        String appKey,
        GatewaySecurityException exception
    ) {
        try {
            callLogRepository.save(
                null,
                UUID.randomUUID().toString().replace("-", ""),
                appKey,
                request.getRequestURI().substring("/openapi".length()),
                request.getMethod(),
                exception.status().value(),
                0,
                null,
                false,
                clientIp(request),
                exception.getMessage()
            );
        } catch (RuntimeException ignored) {
            // Authentication failures must still return even if the audit store is unavailable.
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",")[0].trim();
    }
}
