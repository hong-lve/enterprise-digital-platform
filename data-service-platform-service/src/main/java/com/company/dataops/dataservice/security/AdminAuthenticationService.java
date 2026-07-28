package com.company.dataops.dataservice.security;

import com.company.dataops.dataservice.domain.AdminSession;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.repository.AdminSecurityRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAuthenticationService {
    private final AdminSecurityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecretCryptoService cryptoService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration sessionDuration;
    private final Duration lockDuration;
    private final int maxLoginAttempts;

    public AdminAuthenticationService(
        AdminSecurityRepository repository,
        PasswordEncoder passwordEncoder,
        SecretCryptoService cryptoService,
        @Value("${platform.data-service.admin.session-duration:8h}") Duration sessionDuration,
        @Value("${platform.data-service.admin.lock-duration:15m}") Duration lockDuration,
        @Value("${platform.data-service.admin.max-login-attempts:5}") int maxLoginAttempts
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.cryptoService = cryptoService;
        this.sessionDuration = sessionDuration;
        this.lockDuration = lockDuration;
        this.maxLoginAttempts = maxLoginAttempts;
    }

    public AdminSession login(
        String username,
        String password,
        String clientIp,
        String userAgent
    ) {
        String normalizedUsername = username == null ? "" : username.trim();
        Optional<AdminUserRecord> optionalUser = repository.findUserByUsername(normalizedUsername);
        if (optionalUser.isEmpty()) {
            repository.auditLogin(null, normalizedUsername, false, "用户名或密码错误", clientIp);
            throw unauthorized();
        }

        AdminUserRecord user = optionalUser.get();
        if (!"ACTIVE".equals(user.status())) {
            repository.auditLogin(user.id(), user.username(), false, "账号已停用", clientIp);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用");
        }
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(Instant.now())) {
            repository.auditLogin(user.id(), user.username(), false, "账号已锁定", clientIp);
            throw new ResponseStatusException(HttpStatus.LOCKED, "登录失败次数过多，请稍后重试");
        }
        if (password == null || !passwordEncoder.matches(password, user.passwordHash())) {
            repository.recordFailedAttempt(user.id(), maxLoginAttempts, lockDuration.toSeconds());
            repository.auditLogin(user.id(), user.username(), false, "用户名或密码错误", clientIp);
            throw unauthorized();
        }

        repository.recordSuccessfulLogin(user.id());
        repository.deleteExpiredSessions();
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(sessionDuration);
        repository.createSession(user.id(), cryptoService.hash(token), expiresAt, clientIp, userAgent);
        repository.auditLogin(user.id(), user.username(), true, null, clientIp);
        AdminUserRecord refreshed = repository.findUserByUsername(user.username()).orElseThrow();
        return new AdminSession(token, expiresAt, refreshed);
    }

    public Optional<AdminUserRecord> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = cryptoService.hash(token);
        Optional<AdminUserRecord> user = repository.findActiveUserBySessionHash(tokenHash);
        user.ifPresent(ignored -> repository.touchSession(tokenHash));
        return user;
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            repository.revokeSession(cryptoService.hash(token));
        }
    }

    public static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isBlank() ? null : token;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "dsp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }
}
