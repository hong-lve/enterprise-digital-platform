package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.repository.AdminSecurityRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/data-service-admin/admin-users")
public class AdminUserController {
    private final AdminSecurityRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(AdminSecurityRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public ApiResponse<List<AdminUserRecord>> list() {
        return ApiResponse.ok(repository.findAllUsers());
    }

    @GetMapping("/roles")
    public ApiResponse<List<AdminSecurityRepository.RoleOption>> roles() {
        return ApiResponse.ok(repository.findAllRoles());
    }

    @PostMapping
    public ApiResponse<AdminUserRecord> create(@Valid @RequestBody CreateAdminUserRequest request) {
        String username = request.username().trim();
        if (!username.matches("[A-Za-z][A-Za-z0-9_.-]{2,79}")) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "用户名需以字母开头，只能包含字母、数字、点、下划线和短横线"
            );
        }
        validateRoles(request.roleCodes());
        try {
            return ApiResponse.ok(repository.createUser(
                username,
                passwordEncoder.encode(request.password()),
                request.displayName().trim(),
                request.roleCodes()
            ));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
    }

    @PostMapping("/{id}/roles")
    public ApiResponse<AdminUserRecord> replaceRoles(
        @PathVariable long id,
        @Valid @RequestBody RoleRequest request
    ) {
        requireUser(id);
        validateRoles(request.roleCodes());
        return ApiResponse.ok(repository.replaceRoles(id, request.roleCodes()));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<AdminUserRecord> changeStatus(
        @PathVariable long id,
        @Valid @RequestBody StatusRequest request,
        Authentication authentication
    ) {
        AdminUserRecord current = (AdminUserRecord) authentication.getPrincipal();
        String action = request.action().toUpperCase(Locale.ROOT);
        if (current.id() == id && "DISABLE".equals(action)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用当前登录账号");
        }
        requireUser(id);
        String status = switch (action) {
            case "ENABLE" -> "ACTIVE";
            case "DISABLE" -> "DISABLED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态动作只支持 ENABLE 或 DISABLE");
        };
        return ApiResponse.ok(repository.updateUserStatus(id, status));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(
        @PathVariable long id,
        @Valid @RequestBody ResetPasswordRequest request
    ) {
        requireUser(id);
        repository.resetPassword(id, passwordEncoder.encode(request.password()));
        return ApiResponse.ok(null);
    }

    private void validateRoles(Set<String> roleCodes) {
        Set<String> existing = repository.findExistingRoleCodes(roleCodes);
        if (!existing.equals(roleCodes)) {
            Set<String> unknown = new java.util.LinkedHashSet<>(roleCodes);
            unknown.removeAll(existing);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不存在：" + String.join(", ", unknown));
        }
    }

    private AdminUserRecord requireUser(long id) {
        return repository.findUserById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "管理账号不存在"));
    }

    public record CreateAdminUserRequest(
        @NotBlank String username,
        @NotBlank String displayName,
        @NotBlank @Size(min = 12, max = 128, message = "密码长度需为 12-128 位") String password,
        @NotEmpty(message = "至少分配一个角色") Set<String> roleCodes
    ) {
    }

    public record RoleRequest(
        @NotEmpty(message = "至少分配一个角色") Set<String> roleCodes
    ) {
    }

    public record StatusRequest(@NotBlank String action) {
    }

    public record ResetPasswordRequest(
        @NotBlank @Size(min = 12, max = 128, message = "密码长度需为 12-128 位") String password
    ) {
    }
}
