package com.company.dataops.console.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.MenuEntity;
import com.company.dataops.console.entity.RoleMenuEntity;
import com.company.dataops.console.entity.UserEntity;
import com.company.dataops.console.entity.UserRoleEntity;
import com.company.dataops.console.mapper.MenuMapper;
import com.company.dataops.console.mapper.RoleMenuMapper;
import com.company.dataops.console.mapper.UserMapper;
import com.company.dataops.console.mapper.UserRoleMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class LocalAuthorityService {
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuMapper menuMapper;

    public LocalAuthorityService(UserMapper userMapper, UserRoleMapper userRoleMapper, RoleMenuMapper roleMenuMapper, MenuMapper menuMapper) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.menuMapper = menuMapper;
    }

    public List<String> permissionsFor(String username) {
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (user == null) {
            return List.of();
        }
        return assignedMenusFor(user.getId())
            .stream()
            .map(MenuEntity::getPermission)
            .filter(permission -> permission != null && !permission.isBlank())
            .distinct()
            .toList();
    }

    /**
     * The user -> role -> menu resolution AuthController.me() needs for its
     * menu tree and permission list - previously reimplemented there
     * separately from permissionsFor() above, which resolves the same chain
     * for the Spring Security authorities. Kept in one place so a future
     * change to how roles grant menus (e.g. adding a data-scope filter)
     * can't update one call site and silently miss the other.
     */
    public List<Long> roleIdsFor(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRoleEntity>())
            .stream()
            .filter(row -> Objects.equals(row.getUserId(), userId))
            .map(UserRoleEntity::getRoleId)
            .toList();
    }

    public List<MenuEntity> assignedMenusFor(Long userId) {
        List<Long> roleIds = roleIdsFor(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<RoleMenuEntity>())
            .stream()
            .filter(row -> roleIds.contains(row.getRoleId()))
            .map(RoleMenuEntity::getMenuId)
            .distinct()
            .toList();
        return menuIds.isEmpty() ? List.of() : menuMapper.selectBatchIds(menuIds);
    }
}
