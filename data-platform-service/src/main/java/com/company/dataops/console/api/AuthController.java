package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.LoginLogEntity;
import com.company.dataops.console.entity.MenuEntity;
import com.company.dataops.console.entity.RoleEntity;
import com.company.dataops.console.entity.UserEntity;
import com.company.dataops.console.entity.UserTotpEntity;
import com.company.dataops.console.mapper.LoginLogMapper;
import com.company.dataops.console.mapper.RoleMapper;
import com.company.dataops.console.mapper.UserMapper;
import com.company.dataops.console.mapper.UserTotpMapper;
import com.company.dataops.console.security.DataSourceCredentialCrypto;
import com.company.dataops.console.security.LocalAuthorityService;
import com.company.dataops.console.security.LoginRateLimiter;
import com.company.dataops.console.security.TotpService;
import com.company.dataops.console.security.TwoFactorPendingLoginStore;
import com.company.dataops.console.security.TwoFactorSettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final LoginLogMapper loginLogMapper;
    private final AuthenticationManager authenticationManager;
    private final LoginRateLimiter rateLimiter;
    private final LocalAuthorityService authorityService;
    private final TwoFactorSettingService twoFactorSettingService;
    private final TwoFactorPendingLoginStore pendingLoginStore;
    private final UserTotpMapper userTotpMapper;
    private final TotpService totpService;

    public AuthController(
        UserMapper userMapper,
        RoleMapper roleMapper,
        LoginLogMapper loginLogMapper,
        AuthenticationManager authenticationManager,
        LoginRateLimiter rateLimiter,
        LocalAuthorityService authorityService,
        TwoFactorSettingService twoFactorSettingService,
        TwoFactorPendingLoginStore pendingLoginStore,
        UserTotpMapper userTotpMapper,
        TotpService totpService
    ) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.loginLogMapper = loginLogMapper;
        this.authenticationManager = authenticationManager;
        this.rateLimiter = rateLimiter;
        this.authorityService = authorityService;
        this.twoFactorSettingService = twoFactorSettingService;
        this.pendingLoginStore = pendingLoginStore;
        this.userTotpMapper = userTotpMapper;
        this.totpService = totpService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        String ip = resolveIpAddress(servletRequest);
        rateLimiter.assertNotLocked(request.username(), ip);
        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
            rateLimiter.recordSuccess(request.username(), ip);

            if (!twoFactorSettingService.isEnabled()) {
                completeLogin(authentication, servletRequest);
                saveLoginLog(request.username(), servletRequest, "SUCCESS", "登录成功");
                return ApiResponse.ok(LoginResult.done());
            }

            // Password is verified at this point, but the session isn't
            // established yet - completeLogin() only runs after /auth/2fa/verify
            // succeeds, so a correct password alone never grants access while
            // 2FA is on.
            UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, request.username()));
            UserTotpEntity existingTotp = userTotpMapper.selectById(user.getId());
            if (existingTotp != null) {
                String pendingToken = pendingLoginStore.create(authentication, user.getId(), null);
                return ApiResponse.ok(LoginResult.requiresVerify(pendingToken));
            }
            String newSecret = totpService.generateSecret();
            String pendingToken = pendingLoginStore.create(authentication, user.getId(), newSecret);
            String otpAuthUri = totpService.buildOtpAuthUri(newSecret, request.username());
            return ApiResponse.ok(LoginResult.requiresSetup(pendingToken, newSecret, otpAuthUri));
        } catch (AuthenticationException exception) {
            rateLimiter.recordFailure(request.username(), ip);
            saveLoginLog(request.username(), servletRequest, "FAILED", "用户名或密码错误");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误", exception);
        }
    }

    @PostMapping("/2fa/verify")
    public ApiResponse<LoginResult> verifyTwoFactor(@Valid @RequestBody VerifyTwoFactorRequest request, HttpServletRequest servletRequest) {
        TwoFactorPendingLoginStore.PendingLogin pending = pendingLoginStore.get(request.pendingToken());
        if (pending == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "验证已过期，请重新登录");
        }
        String secret = pending.pendingSecret != null ? pending.pendingSecret : decryptSecret(pending.userId);
        if (secret == null || !totpService.verifyCode(secret, request.code())) {
            int attempts = pendingLoginStore.recordFailedAttempt(request.pendingToken());
            saveLoginLog(pending.authentication.getName(), servletRequest, "FAILED", "双因子验证码错误");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, attempts >= 8 ? "验证失败次数过多，请重新登录" : "验证码错误");
        }
        if (pending.pendingSecret != null) {
            UserTotpEntity totp = new UserTotpEntity();
            totp.setUserId(pending.userId);
            totp.setSecretEncrypted(DataSourceCredentialCrypto.encrypt(pending.pendingSecret));
            totp.setConfirmedAt(LocalDateTime.now());
            userTotpMapper.insert(totp);
        }
        completeLogin(pending.authentication, servletRequest);
        pendingLoginStore.consume(request.pendingToken());
        saveLoginLog(pending.authentication.getName(), servletRequest, "SUCCESS", "登录成功");
        return ApiResponse.ok(LoginResult.done());
    }

    private String decryptSecret(Long userId) {
        UserTotpEntity totp = userTotpMapper.selectById(userId);
        return totp == null ? null : DataSourceCredentialCrypto.tryDecrypt(totp.getSecretEncrypted());
    }

    private void completeLogin(Authentication authentication, HttpServletRequest servletRequest) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        servletRequest.getSession(true).setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            SecurityContextHolder.getContext()
        );
    }

    public record LoginResult(boolean requires2fa, boolean requiresSetup, String pendingToken, String secret, String otpAuthUri) {
        static LoginResult done() {
            return new LoginResult(false, false, null, null, null);
        }

        static LoginResult requiresVerify(String pendingToken) {
            return new LoginResult(true, false, pendingToken, null, null);
        }

        static LoginResult requiresSetup(String pendingToken, String secret, String otpAuthUri) {
            return new LoginResult(true, true, pendingToken, secret, otpAuthUri);
        }
    }

    public record VerifyTwoFactorRequest(
        @NotBlank(message = "pendingToken 不能为空") String pendingToken,
        @NotBlank(message = "验证码不能为空") String code
    ) {
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserView> me(Authentication authentication) {
        UserEntity user = requireCurrentUser(authentication);
        Long userId = user.getId();
        List<Long> roleIds = authorityService.roleIdsFor(userId);
        List<RoleEntity> roles = roleIds.isEmpty() ? List.of() : roleMapper.selectBatchIds(roleIds);
        List<MenuEntity> assignedMenus = authorityService.assignedMenusFor(userId);
        List<MenuEntity> visibleMenus = assignedMenus
            .stream()
            .filter(menu -> "Y".equals(menu.getVisible()))
            .sorted(Comparator.comparing(MenuEntity::getSortOrder))
            .toList();
        List<String> permissions = assignedMenus.stream()
            .map(MenuEntity::getPermission)
            .filter(permission -> permission != null && !permission.isBlank())
            .distinct()
            .toList();

        return ApiResponse.ok(new CurrentUserView(
            user.getId(),
            user.getUsername(),
            user.getDisplayName(),
            roles.stream().map(RoleEntity::getRoleKey).toList(),
            permissions,
            buildTree(visibleMenus)
        ));
    }

    private UserEntity requireCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return user;
    }

    private List<MenuView> buildTree(List<MenuEntity> menus) {
        Map<Long, MenuView> index = new LinkedHashMap<>();
        List<MenuView> roots = new ArrayList<>();
        for (MenuEntity menu : menus) {
            if ("BUTTON".equals(menu.getType())) {
                continue;
            }
            index.put(menu.getId(), new MenuView(menu.getId(), menu.getParentId(), menu.getTitle(), menu.getPath(), menu.getIcon(), new ArrayList<>()));
        }
        for (MenuView menu : index.values()) {
            if (menu.parentId() == null || menu.parentId() == 0 || !index.containsKey(menu.parentId())) {
                roots.add(menu);
            } else {
                index.get(menu.parentId()).children().add(menu);
            }
        }
        return roots;
    }

    public record CurrentUserView(Long id, String username, String displayName, List<String> roles, List<String> permissions, List<MenuView> menus) {
    }

    public record MenuView(Long id, Long parentId, String title, String path, String icon, List<MenuView> children) {
    }

    public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
    ) {
    }

    private void saveLoginLog(String username, HttpServletRequest request, String result, String message) {
        LoginLogEntity log = new LoginLogEntity();
        log.setUsername(username == null || username.isBlank() ? "unknown" : username);
        log.setIpAddress(resolveIpAddress(request));
        log.setUserAgent(request.getHeader("User-Agent"));
        log.setResult(result);
        log.setMessage(message);
        loginLogMapper.insert(log);
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
}
