package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminSession;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.security.AdminAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data-service-admin/auth")
public class AdminAuthenticationController {
    private final AdminAuthenticationService authenticationService;
    private final boolean secureCookie;

    public AdminAuthenticationController(
        AdminAuthenticationService authenticationService,
        @Value("${platform.data-service.admin.secure-cookie:false}") boolean secureCookie
    ) {
        this.authenticationService = authenticationService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public ApiResponse<AdminSession> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse servletResponse
    ) {
        AdminSession session = authenticationService.login(
            request.username(),
            request.password(),
            clientIp(servletRequest),
            servletRequest.getHeader("User-Agent")
        );
        ResponseCookie cookie = ResponseCookie.from("DSP_ADMIN_SESSION", session.token())
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Strict")
            .path("/data-service-admin")
            .maxAge(Duration.between(java.time.Instant.now(), session.expiresAt()))
            .build();
        servletResponse.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ApiResponse.ok(session);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authenticationService.logout(resolveToken(request));
        ResponseCookie cookie = ResponseCookie.from("DSP_ADMIN_SESSION", "")
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Strict")
            .path("/data-service-admin")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<AdminUserRecord> me(Authentication authentication) {
        return ApiResponse.ok((AdminUserRecord) authentication.getPrincipal());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
            ? request.getRemoteAddr()
            : forwarded.split(",")[0].trim();
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = AdminAuthenticationService.bearerToken(request.getHeader("Authorization"));
        if (bearer != null) {
            return bearer;
        }
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if ("DSP_ADMIN_SESSION".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
    ) {
    }
}
