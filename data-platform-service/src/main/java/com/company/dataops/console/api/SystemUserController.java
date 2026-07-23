package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.RoleEntity;
import com.company.dataops.console.entity.UserEntity;
import com.company.dataops.console.entity.UserRoleEntity;
import com.company.dataops.console.mapper.RoleMapper;
import com.company.dataops.console.mapper.UserMapper;
import com.company.dataops.console.mapper.UserRoleMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account management for sys_user - previously the only account was the one
 * row seeded by V22, created/edited by hand-writing a migration. Roles are
 * assigned here (via /roles) rather than folded into create()/update() so
 * the "who can grant roles" permission (system:user:assign-role) can differ
 * from "who can edit a display name" (system:user:update), same split
 * SystemRoleController uses for role -> menu assignment.
 */
@RestController
@RequestMapping("/system/users")
public class SystemUserController {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public SystemUserController(UserMapper userMapper, RoleMapper roleMapper, UserRoleMapper userRoleMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:user:view')")
    public ApiResponse<PageResult<UserView>> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long pageSize, @RequestParam(required = false) String username) {
        Page<UserEntity> page = userMapper.selectPage(Page.of(current, pageSize), new LambdaQueryWrapper<UserEntity>()
            .like(username != null && !username.isBlank(), UserEntity::getUsername, username)
            .orderByDesc(UserEntity::getId));
        List<Long> userIds = page.getRecords().stream().map(UserEntity::getId).toList();
        Map<Long, List<String>> roleNamesByUserId = userIds.isEmpty() ? Map.of() : roleNamesByUserId(userIds);
        List<UserView> views = page.getRecords().stream()
            .map(user -> toView(user, roleNamesByUserId.getOrDefault(user.getId(), List.of())))
            .toList();
        return ApiResponse.ok(new PageResult<>(page.getTotal(), views));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:user:create')")
    public ApiResponse<Void> create(@Valid @RequestBody CreateUserRequest request) {
        if (userMapper.selectCount(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, request.username())) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setDisplayName(request.displayName());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus("ENABLED");
        userMapper.insert(user);
        if (request.roleIds() != null && !request.roleIds().isEmpty()) {
            request.roleIds().forEach(roleId -> {
                UserRoleEntity link = new UserRoleEntity();
                link.setUserId(user.getId());
                link.setRoleId(roleId);
                userRoleMapper.insert(link);
            });
        }
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:update')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        UserEntity user = requireUser(id);
        user.setDisplayName(request.displayName());
        user.setEmail(request.email());
        if (request.status() != null && !request.status().isBlank()) {
            user.setStatus(request.status());
        }
        userMapper.updateById(user);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        UserEntity user = requireUser(id);
        if (user.getUsername().equals(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除自己的账号");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleEntity>().eq(UserRoleEntity::getUserId, id));
        userMapper.deleteById(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('system:user:reset-password')")
    public ApiResponse<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        UserEntity user = requireUser(id);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userMapper.updateById(user);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:assign-role')")
    public ApiResponse<List<Long>> roles(@PathVariable Long id) {
        requireUser(id);
        return ApiResponse.ok(userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>().eq(UserRoleEntity::getUserId, id))
            .stream()
            .map(UserRoleEntity::getRoleId)
            .toList());
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:assign-role')")
    public ApiResponse<Void> assignRoles(@PathVariable Long id, @RequestBody AssignRolesRequest request) {
        UserEntity user = requireUser(id);
        if (isLastAdmin(user.getId(), request.roleIds())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该操作会导致系统没有任何管理员账号，已阻止");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleEntity>().eq(UserRoleEntity::getUserId, id));
        request.roleIds().forEach(roleId -> {
            UserRoleEntity link = new UserRoleEntity();
            link.setUserId(id);
            link.setRoleId(roleId);
            userRoleMapper.insert(link);
        });
        return ApiResponse.ok();
    }

    // True when userId currently holds the ADMIN role, no other user does,
    // and newRoleIds would drop it - the only guard against every ADMIN role
    // grant being edited away at once and leaving nobody able to grant it
    // back (there's no superuser bypass elsewhere, see LocalAuthorityService).
    private boolean isLastAdmin(Long userId, List<Long> newRoleIds) {
        RoleEntity adminRole = roleMapper.selectOne(new LambdaQueryWrapper<RoleEntity>().eq(RoleEntity::getRoleKey, "ADMIN"));
        if (adminRole == null || (newRoleIds != null && newRoleIds.contains(adminRole.getId()))) {
            return false;
        }
        List<Long> adminUserIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>().eq(UserRoleEntity::getRoleId, adminRole.getId()))
            .stream()
            .map(UserRoleEntity::getUserId)
            .toList();
        return adminUserIds.size() == 1 && adminUserIds.contains(userId);
    }

    private Map<Long, List<String>> roleNamesByUserId(List<Long> userIds) {
        List<UserRoleEntity> links = userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>().in(UserRoleEntity::getUserId, userIds));
        List<Long> roleIds = links.stream().map(UserRoleEntity::getRoleId).distinct().toList();
        Map<Long, String> roleNameById = roleIds.isEmpty() ? Map.of() : roleMapper.selectBatchIds(roleIds).stream()
            .collect(Collectors.toMap(RoleEntity::getId, RoleEntity::getName));
        return links.stream().collect(Collectors.groupingBy(
            UserRoleEntity::getUserId,
            Collectors.mapping(link -> roleNameById.getOrDefault(link.getRoleId(), "?"), Collectors.toList())
        ));
    }

    private UserView toView(UserEntity user, List<String> roleNames) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getStatus(), roleNames, user.getCreatedAt());
    }

    private UserEntity requireUser(Long id) {
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    public record UserView(Long id, String username, String displayName, String email, String status, List<String> roleNames, java.time.LocalDateTime createdAt) {
    }

    public record CreateUserRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "显示名称不能为空") String displayName,
        String email,
        @NotBlank(message = "密码不能为空") String password,
        List<Long> roleIds
    ) {
    }

    public record UpdateUserRequest(
        @NotBlank(message = "显示名称不能为空") String displayName,
        String email,
        String status
    ) {
    }

    public record ResetPasswordRequest(@NotBlank(message = "密码不能为空") String newPassword) {
    }

    public record AssignRolesRequest(@NotNull @NotEmpty(message = "至少保留一个角色") List<Long> roleIds) {
    }
}
