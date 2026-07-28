package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.MenuEntity;
import com.company.dataops.console.entity.RoleMenuEntity;
import com.company.dataops.console.mapper.MenuMapper;
import com.company.dataops.console.mapper.RoleMenuMapper;
import jakarta.validation.Valid;
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
 * Menu/permission-point management for sys_menu. Adding a MENU-type row here
 * only registers metadata (title/path/icon/permission) that a role can then
 * be granted - it does not create a working page: AppShell.tsx's sidebar and
 * App.tsx's <Route> table are still a fixed, hand-coded set, so a new path
 * with no matching <Route> renders nothing when clicked. This screen is for
 * editing/reordering/hiding the existing fixed set of pages and adding new
 * BUTTON-type permission points (which have no route to matter for) - not
 * for standing up new pages without a code change.
 */
@RestController
@RequestMapping("/system/menus")
public class SystemMenuController {
    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;

    public SystemMenuController(MenuMapper menuMapper, RoleMenuMapper roleMenuMapper) {
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:menu:view')")
    public ApiResponse<List<MenuEntity>> list() {
        return ApiResponse.ok(menuMapper.selectList(new LambdaQueryWrapper<MenuEntity>()
            .orderByAsc(MenuEntity::getParentId)
            .orderByAsc(MenuEntity::getSortOrder)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:create')")
    public ApiResponse<Void> create(@Valid @RequestBody MenuEntity menu) {
        menu.setId(null);
        if (menu.getParentId() == null) {
            menu.setParentId(0L);
        } else if (menu.getParentId() != 0 && menuMapper.selectById(menu.getParentId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级菜单不存在");
        }
        if (menu.getSortOrder() == null) {
            menu.setSortOrder(0);
        }
        if (menu.getVisible() == null || menu.getVisible().isBlank()) {
            menu.setVisible("Y");
        }
        menuMapper.insert(menu);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:update')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody MenuEntity menu) {
        requireMenu(id);
        if (menu.getParentId() != null && menu.getParentId() != 0 && menu.getParentId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级菜单不能是自己");
        }
        menu.setId(id);
        menuMapper.updateById(menu);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireMenu(id);
        if (menuMapper.selectCount(new LambdaQueryWrapper<MenuEntity>().eq(MenuEntity::getParentId, id)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先删除该菜单下的子菜单/按钮");
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenuEntity>().eq(RoleMenuEntity::getMenuId, id));
        menuMapper.deleteById(id);
        return ApiResponse.ok();
    }

    private MenuEntity requireMenu(Long id) {
        MenuEntity menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜单不存在");
        }
        return menu;
    }
}
