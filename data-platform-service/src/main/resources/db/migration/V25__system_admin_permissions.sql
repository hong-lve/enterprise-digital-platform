-- Menu/role/user management admin UI (SystemUserController/SystemRoleController/
-- SystemMenuController) - previously sys_user/sys_role/sys_menu could only be
-- edited by hand-writing SQL migrations, there was no in-app way to add a
-- user, create a role, or change what a role can see.
INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(79, 0, '系统管理', '/system', 'Layout', 'SettingOutlined', 'system:view', 'MENU', 90, 'Y'),
(80, 79, '用户管理', '/system/users', 'SystemUsersPage', 'UserOutlined', 'system:user:view', 'MENU', 1, 'Y'),
(81, 79, '角色管理', '/system/roles', 'SystemRolesPage', 'TeamOutlined', 'system:role:view', 'MENU', 2, 'Y'),
(82, 79, '菜单管理', '/system/menus', 'SystemMenusPage', 'MenuOutlined', 'system:menu:view', 'MENU', 3, 'Y'),
(83, 80, '用户管理-新建', NULL, NULL, NULL, 'system:user:create', 'BUTTON', 1, 'Y'),
(84, 80, '用户管理-编辑', NULL, NULL, NULL, 'system:user:update', 'BUTTON', 2, 'Y'),
(85, 80, '用户管理-删除', NULL, NULL, NULL, 'system:user:delete', 'BUTTON', 3, 'Y'),
(86, 80, '用户管理-重置密码', NULL, NULL, NULL, 'system:user:reset-password', 'BUTTON', 4, 'Y'),
(87, 80, '用户管理-分配角色', NULL, NULL, NULL, 'system:user:assign-role', 'BUTTON', 5, 'Y'),
(88, 81, '角色管理-新建', NULL, NULL, NULL, 'system:role:create', 'BUTTON', 1, 'Y'),
(89, 81, '角色管理-编辑', NULL, NULL, NULL, 'system:role:update', 'BUTTON', 2, 'Y'),
(90, 81, '角色管理-删除', NULL, NULL, NULL, 'system:role:delete', 'BUTTON', 3, 'Y'),
(91, 81, '角色管理-分配权限', NULL, NULL, NULL, 'system:role:assign-menu', 'BUTTON', 4, 'Y'),
(92, 82, '菜单管理-新建', NULL, NULL, NULL, 'system:menu:create', 'BUTTON', 1, 'Y'),
(93, 82, '菜单管理-编辑', NULL, NULL, NULL, 'system:menu:update', 'BUTTON', 2, 'Y'),
(94, 82, '菜单管理-删除', NULL, NULL, NULL, 'system:menu:delete', 'BUTTON', 3, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id >= 79;
