package com.company.dataops.console.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.console.entity.MenuEntity;
import com.company.dataops.console.entity.RoleMenuEntity;
import com.company.dataops.console.entity.UserEntity;
import com.company.dataops.console.entity.UserRoleEntity;
import com.company.dataops.console.mapper.MenuMapper;
import com.company.dataops.console.mapper.RoleMenuMapper;
import com.company.dataops.console.mapper.UserMapper;
import com.company.dataops.console.mapper.UserRoleMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * usernamesWithPermission() is the reverse of the already-established
 * permissionsFor() direction (permission -> holders instead of user ->
 * permissions), used to find who to notify for a permission-gated queue
 * without hardcoding a role. The chain it walks (menu -> role -> user) has
 * three points where it should come back empty instead of continuing on to
 * mappers that have nothing meaningful to look up.
 */
class LocalAuthorityServiceTest {
    private static final String PERMISSION = "system:approval:handle";

    private MenuMapper menuMapper;
    private RoleMenuMapper roleMenuMapper;
    private UserRoleMapper userRoleMapper;
    private UserMapper userMapper;
    private LocalAuthorityService service;

    @BeforeEach
    void setUp() {
        menuMapper = mock(MenuMapper.class);
        roleMenuMapper = mock(RoleMenuMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        userMapper = mock(UserMapper.class);
        service = new LocalAuthorityService(userMapper, userRoleMapper, roleMenuMapper, menuMapper);
    }

    private MenuEntity menu(long id, String permission) {
        MenuEntity menu = new MenuEntity();
        menu.setId(id);
        menu.setPermission(permission);
        return menu;
    }

    private RoleMenuEntity roleMenu(long roleId, long menuId) {
        RoleMenuEntity roleMenu = new RoleMenuEntity();
        roleMenu.setRoleId(roleId);
        roleMenu.setMenuId(menuId);
        return roleMenu;
    }

    private UserRoleEntity userRole(long userId, long roleId) {
        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        return userRole;
    }

    private UserEntity user(long id, String username, String status) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(status);
        return user;
    }

    @Test
    void returnsUsernamesOfEnabledUsersWhoseRoleGrantsThePermission() {
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(100L, PERMISSION)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(1L, 100L)));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(1L, 1L)));
        when(userMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(user(1L, "approver", "ENABLED")));

        assertEquals(List.of("approver"), service.usernamesWithPermission(PERMISSION));
    }

    @Test
    void excludesDisabledUsersEvenIfTheirRoleGrantsThePermission() {
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(100L, PERMISSION)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(1L, 100L)));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(1L, 1L)));
        when(userMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(user(1L, "disabled-user", "DISABLED")));

        assertTrue(service.usernamesWithPermission(PERMISSION).isEmpty());
    }

    @Test
    void dedupesAUserHoldingThePermissionThroughTwoDifferentRoles() {
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(100L, PERMISSION)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(1L, 100L), roleMenu(2L, 100L)));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(userRole(1L, 1L), userRole(1L, 2L)));
        when(userMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(user(1L, "admin", "ENABLED")));

        assertEquals(List.of("admin"), service.usernamesWithPermission(PERMISSION));
    }

    @Test
    void shortCircuitsWithoutQueryingFurtherWhenNoMenuHasThePermission() {
        when(menuMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.usernamesWithPermission(PERMISSION).isEmpty());
        verify(roleMenuMapper, never()).selectList(any());
        verify(userRoleMapper, never()).selectList(any());
        verify(userMapper, never()).selectBatchIds(any());
    }

    @Test
    void returnsEmptyWhenTheMenuExistsButNoRoleIsGrantedIt() {
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(100L, PERMISSION)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.usernamesWithPermission(PERMISSION).isEmpty());
        verify(userRoleMapper, never()).selectList(any());
        verify(userMapper, never()).selectBatchIds(any());
    }

    @Test
    void returnsEmptyWhenARoleHasItButNoUserHoldsThatRole() {
        when(menuMapper.selectList(any())).thenReturn(List.of(menu(100L, PERMISSION)));
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(1L, 100L)));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.usernamesWithPermission(PERMISSION).isEmpty());
        verify(userMapper, never()).selectBatchIds(any());
    }
}
