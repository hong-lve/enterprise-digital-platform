-- V22 only seeded the view/execute-type permission points referenced by the
-- sidebar (one per page). Every controller also guards its mutating
-- endpoints (create/update/delete/start/stop/upload/etc.) with a separate,
-- more specific @PreAuthorize permission string that was never seeded
-- anywhere, so no role - including ADMIN - could pass those checks. These
-- are BUTTON-type sys_menu rows: AuthController.buildTree() already treats
-- type='BUTTON' as non-navigable and excludes it from the menu tree, while
-- still including it in the flat permissions list, so this reuses an
-- existing convention rather than inventing a new one.
INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(52, 42, 'CDC 数据源-新建', NULL, NULL, NULL, 'realtime:cdc:create', 'BUTTON', 1, 'Y'),
(53, 42, 'CDC 数据源-编辑', NULL, NULL, NULL, 'realtime:cdc:update', 'BUTTON', 2, 'Y'),
(54, 42, 'CDC 数据源-删除', NULL, NULL, NULL, 'realtime:cdc:delete', 'BUTTON', 3, 'Y'),
(55, 42, 'CDC 数据源-启动', NULL, NULL, NULL, 'realtime:cdc:start', 'BUTTON', 4, 'Y'),
(56, 42, 'CDC 数据源-停止', NULL, NULL, NULL, 'realtime:cdc:stop', 'BUTTON', 5, 'Y'),
(57, 41, '总览-取消孤立任务', NULL, NULL, NULL, 'realtime:overview:cancel-orphan', 'BUTTON', 1, 'Y'),
(58, 41, '总览-删除孤立连接器', NULL, NULL, NULL, 'realtime:overview:delete-orphan-connector', 'BUTTON', 2, 'Y'),
(59, 48, 'SQL 流作业-新建', NULL, NULL, NULL, 'realtime:sql-job:create', 'BUTTON', 1, 'Y'),
(60, 48, 'SQL 流作业-编辑', NULL, NULL, NULL, 'realtime:sql-job:update', 'BUTTON', 2, 'Y'),
(61, 48, 'SQL 流作业-删除', NULL, NULL, NULL, 'realtime:sql-job:delete', 'BUTTON', 3, 'Y'),
(62, 48, 'SQL 流作业-启动', NULL, NULL, NULL, 'realtime:sql-job:start', 'BUTTON', 4, 'Y'),
(63, 48, 'SQL 流作业-停止', NULL, NULL, NULL, 'realtime:sql-job:stop', 'BUTTON', 5, 'Y'),
(64, 48, 'SQL 流作业-清理保存点', NULL, NULL, NULL, 'realtime:sql-job:clear-savepoint', 'BUTTON', 6, 'Y'),
(65, 50, '数据源配置-新建', NULL, NULL, NULL, 'realtime:datasource:create', 'BUTTON', 1, 'Y'),
(66, 50, '数据源配置-编辑', NULL, NULL, NULL, 'realtime:datasource:update', 'BUTTON', 2, 'Y'),
(67, 50, '数据源配置-删除', NULL, NULL, NULL, 'realtime:datasource:delete', 'BUTTON', 3, 'Y'),
(68, 50, '数据源配置-测试连接', NULL, NULL, NULL, 'realtime:datasource:test', 'BUTTON', 4, 'Y'),
(69, 51, 'JAR 包管理-上传', NULL, NULL, NULL, 'realtime:jar:upload', 'BUTTON', 1, 'Y'),
(70, 51, 'JAR 包管理-编辑', NULL, NULL, NULL, 'realtime:jar:update', 'BUTTON', 2, 'Y'),
(71, 51, 'JAR 包管理-删除', NULL, NULL, NULL, 'realtime:jar:delete', 'BUTTON', 3, 'Y'),
(72, 43, 'Flink 流作业-新建', NULL, NULL, NULL, 'realtime:flink:create', 'BUTTON', 1, 'Y'),
(73, 43, 'Flink 流作业-编辑', NULL, NULL, NULL, 'realtime:flink:update', 'BUTTON', 2, 'Y'),
(74, 43, 'Flink 流作业-删除', NULL, NULL, NULL, 'realtime:flink:delete', 'BUTTON', 3, 'Y'),
(75, 43, 'Flink 流作业-启动', NULL, NULL, NULL, 'realtime:flink:start', 'BUTTON', 4, 'Y'),
(76, 43, 'Flink 流作业-停止', NULL, NULL, NULL, 'realtime:flink:stop', 'BUTTON', 5, 'Y'),
(77, 43, 'Flink 流作业-清理保存点', NULL, NULL, NULL, 'realtime:flink:clear-savepoint', 'BUTTON', 6, 'Y'),
(78, 40, '生产环境操作', NULL, NULL, NULL, 'realtime:env:prod-operate', 'BUTTON', 1, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id >= 52;
