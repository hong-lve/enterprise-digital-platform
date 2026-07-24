-- sys_login_log only ever tracked login attempts. Every mutating action
-- past that point (who created/edited/deleted a data source, a role's
-- permissions, a CDC source, a JAR, ...) left no trail beyond whatever
-- that entity's own updated_at column happens to show - which says
-- "something changed at time T", never "who" or "via what action". This
-- is a generic cross-cutting log (AuditLogInterceptor) rather than a
-- hand-added call in every mutating controller method - it can't always
-- say exactly what changed (the mutating request body isn't logged, since
-- several of those bodies carry secrets - data source passwords, TOTP
-- secrets - that must never end up in a log table), but "who hit which
-- endpoint, when, and did it succeed" is still real, previously-nonexistent
-- accountability.
CREATE TABLE audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(100),
  ip_address VARCHAR(64),
  http_method VARCHAR(10) NOT NULL,
  path VARCHAR(300) NOT NULL,
  permission VARCHAR(100) COMMENT '命中的 @PreAuthorize 权限点，能确定的话',
  status VARCHAR(10) NOT NULL COMMENT 'SUCCESS/FAILURE',
  error_message VARCHAR(500),
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_audit_log_username (username),
  INDEX idx_audit_log_occurred_at (occurred_at)
);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(107, 79, '操作审计', '/system/audit-log', 'AuditLogPage', 'AuditOutlined', 'system:audit:view', 'MENU', 5, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id >= 107;
