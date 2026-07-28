package com.company.dataops.dataservice.security;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAuthenticationFilter extends OncePerRequestFilter {
    private final AdminAuthenticationService authenticationService;
    private final ObjectMapper objectMapper;

    public AdminAuthenticationFilter(
        AdminAuthenticationService authenticationService,
        ObjectMapper objectMapper
    ) {
        this.authenticationService = authenticationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean securedPath = path.startsWith("/data-service-admin/")
            || (path.startsWith("/actuator/")
                && !path.startsWith("/actuator/health")
                && !"/actuator/prometheus".equals(path))
            || path.startsWith("/v3/api-docs/")
            || path.startsWith("/swagger-ui");
        return !securedPath || "/data-service-admin/auth/login".equals(path);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);
        AdminUserRecord user = authenticationService.authenticate(token).orElse(null);
        if (user == null) {
            unauthorized(response);
            return;
        }

        List<SimpleGrantedAuthority> authorities = user.permissions().stream()
            .map(SimpleGrantedAuthority::new)
            .toList();
        UsernamePasswordAuthenticationToken authentication =
            UsernamePasswordAuthenticationToken.authenticated(user, token, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = AdminAuthenticationService.bearerToken(request.getHeader("Authorization"));
        if (bearer != null) {
            return bearer;
        }
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if ("DSP_ADMIN_SESSION".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(401, "登录已失效，请重新登录"));
    }
}
