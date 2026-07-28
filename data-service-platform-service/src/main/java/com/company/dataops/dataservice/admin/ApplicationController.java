package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.ApplicationRecord;
import com.company.dataops.dataservice.domain.ApplicationSecretVersion;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.CreatedApplication;
import com.company.dataops.dataservice.repository.ApplicationRepository;
import com.company.dataops.dataservice.security.SecretCryptoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/data-service-admin/applications")
public class ApplicationController {
    private final ApplicationRepository repository;
    private final SecretCryptoService cryptoService;

    public ApplicationController(
        ApplicationRepository repository,
        SecretCryptoService cryptoService
    ) {
        this.repository = repository;
        this.cryptoService = cryptoService;
    }

    @GetMapping
    public ApiResponse<List<ApplicationRecord>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @PostMapping
    public ApiResponse<CreatedApplication> create(@Valid @RequestBody CreateApplicationRequest request) {
        String appKey = request.appKey() == null || request.appKey().isBlank()
            ? "app_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            : request.appKey().trim();
        if (!appKey.matches("[A-Za-z0-9_-]{6,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AppKey 只能包含字母、数字、下划线和短横线");
        }
        String secret = cryptoService.generateSecret();
        try {
            ApplicationRecord application = repository.create(
                appKey,
                cryptoService.hash(secret),
                cryptoService.encrypt(secret),
                request.name(),
                request.description(),
                request.qpsLimit()
            );
            return ApiResponse.ok(new CreatedApplication(application, secret));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AppKey 已存在");
        }
    }

    @PostMapping("/{id}/status")
    public ApiResponse<ApplicationRecord> changeStatus(
        @PathVariable long id,
        @Valid @RequestBody ChangeApplicationStatusRequest request
    ) {
        String status = switch (request.action().toUpperCase()) {
            case "ENABLE" -> "ENABLED";
            case "DISABLE" -> "DISABLED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的应用状态操作");
        };
        return ApiResponse.ok(repository.updateStatus(id, status));
    }

    @PostMapping("/{id}/rotate-secret")
    public ApiResponse<CreatedApplication> rotateSecret(
        @PathVariable long id,
        @RequestBody(required = false) RotateSecretRequest request,
        Authentication authentication
    ) {
        int graceHours = request == null || request.graceHours() == null ? 24 : request.graceHours();
        if (graceHours < 1 || graceHours > 168) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密钥宽限期必须在 1 到 168 小时之间");
        }
        String secret = cryptoService.generateSecret();
        ApplicationRecord application = repository.rotateSecret(
            id,
            cryptoService.hash(secret),
            cryptoService.encrypt(secret),
            Duration.ofHours(graceHours),
            actor(authentication)
        );
        return ApiResponse.ok(new CreatedApplication(application, secret));
    }

    @GetMapping("/{id}/credentials")
    public ApiResponse<List<ApplicationSecretVersion>> credentials(@PathVariable long id) {
        if (repository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "应用不存在");
        }
        return ApiResponse.ok(repository.findSecretVersions(id));
    }

    @PostMapping("/{id}/credentials/{version}/revoke")
    public ApiResponse<ApplicationSecretVersion> revokeCredential(
        @PathVariable long id,
        @PathVariable int version,
        Authentication authentication
    ) {
        return ApiResponse.ok(repository.revokeSecret(id, version, actor(authentication)));
    }

    @PostMapping("/{id}/authorizations")
    public ApiResponse<ApplicationRecord> replaceAuthorizations(
        @PathVariable long id,
        @Valid @RequestBody AuthorizationRequest request
    ) {
        throw new ResponseStatusException(
            HttpStatus.GONE,
            "直接授权已升级为 API 订阅审批，请使用订阅管理"
        );
    }

    public record CreateApplicationRequest(
        String appKey,
        @NotBlank(message = "应用名称不能为空") String name,
        String description,
        @NotNull @Min(1) @Max(10000) Integer qpsLimit
    ) {
    }

    public record ChangeApplicationStatusRequest(@NotBlank String action) {
    }

    public record AuthorizationRequest(@NotNull List<Long> apiIds) {
    }

    public record RotateSecretRequest(Integer graceHours) {
    }

    private static String actor(Authentication authentication) {
        return ((AdminUserRecord) authentication.getPrincipal()).username();
    }
}
