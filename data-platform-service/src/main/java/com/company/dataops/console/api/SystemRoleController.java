package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.RoleEntity;
import com.company.dataops.console.entity.RoleMenuEntity;
import com.company.dataops.console.entity.UserRoleEntity;
import com.company.dataops.console.mapper.RoleMapper;
import com.company.dataops.console.mapper.RoleMenuMapper;
import com.company.dataops.console.mapper.UserRoleMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Role management for sys_role, and the role -> menu/permission grants
 * (sys_role_menu) that used to only be editable by hand-writing a migration
 * (see V22/V23's seed INSERTs). dataScope isn't exposed here even though
 * RoleEntity has the column - nothing in this codebase reads it, so
 * surfacing it as a configurable option would imply row-level filtering
 * that doesn't actually happen.
 */
@RestController
@RequestMapping("/system/roles")
public class SystemRoleController {
    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final UserRoleMapper userRoleMapper;

    public SystemRoleController(RoleMapper roleMapper, RoleMenuMapper roleMenuMapper, UserRoleMapper userRoleMapper) {
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:role:view')")
    public ApiResponse<List<RoleEntity>> list() {
        return ApiResponse.ok(roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>().orderByAsc(RoleEntity::getId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:role:create')")
    public ApiResponse<Void> create(@Valid @RequestBody CreateRoleRequest request) {
        if (roleMapper.selectCount(new LambdaQueryWrapper<RoleEntity>().eq(RoleEntity::getRoleKey, request.roleKey())) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色标识已存在");
        }
        RoleEntity role = new RoleEntity();
        role.setRoleKey(request.roleKey());
        role.setName(request.name());
        role.setDescription(request.description());
        role.setStatus("ENABLED");
        role.setDataScope("ALL");
        roleMapper.insert(role);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:update')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        RoleEntity role = requireRole(id);
        role.setName(request.name());
        role.setDescription(request.description());
        if (request.status() != null && !request.status().isBlank()) {
            role.setStatus(request.status());
        }
        roleMapper.updateById(role);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        RoleEntity role = requireRole(id);
        if ("ADMIN".equals(role.getRoleKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置管理员角色不能删除");
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenuEntity>().eq(RoleMenuEntity::getRoleId, id));
        userRoleMapper.delete(new LambdaQueryWrapper<UserRoleEntity>().eq(UserRoleEntity::getRoleId, id));
        roleMapper.deleteById(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('system:role:assign-menu')")
    public ApiResponse<List<Long>> menus(@PathVariable Long id) {
        requireRole(id);
        return ApiResponse.ok(roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenuEntity>().eq(RoleMenuEntity::getRoleId, id))
            .stream()
            .map(RoleMenuEntity::getMenuId)
            .toList());
    }

    @PutMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('system:role:assign-menu')")
    public ApiResponse<Void> assignMenus(@PathVariable Long id, @RequestBody AssignMenusRequest request) {
        requireRole(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenuEntity>().eq(RoleMenuEntity::getRoleId, id));
        List<Long> menuIds = request.menuIds() == null ? List.of() : request.menuIds();
        menuIds.forEach(menuId -> {
            RoleMenuEntity link = new RoleMenuEntity();
            link.setRoleId(id);
            link.setMenuId(menuId);
            roleMenuMapper.insert(link);
        });
        return ApiResponse.ok();
    }

    private RoleEntity requireRole(Long id) {
        RoleEntity role = roleMapper.selectById(id);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    public record CreateRoleRequest(
        @NotBlank(message = "角色标识不能为空") String roleKey,
        @NotBlank(message = "角色名称不能为空") String name,
        String description
    ) {
    }

    public record UpdateRoleRequest(
        @NotBlank(message = "角色名称不能为空") String name,
        String description,
        String status
    ) {
    }

    public record AssignMenusRequest(List<Long> menuIds) {
    }
}
