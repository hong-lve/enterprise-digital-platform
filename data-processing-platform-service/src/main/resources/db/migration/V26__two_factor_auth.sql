-- Global TOTP two-factor login toggle, admin-configurable at runtime (no
-- redeploy needed) - single-row settings table rather than a per-user flag,
-- since the requirement is "the whole system requires 2FA or it doesn't",
-- not per-user opt-in.
CREATE TABLE sys_two_factor_setting (
  id TINYINT NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

INSERT INTO sys_two_factor_setting (id, enabled) VALUES (1, 0);

-- One row per user who has completed TOTP enrollment (confirmed by entering
-- a valid code from their authenticator app, not just requested a QR code) -
-- secret_encrypted uses the same AES-256-GCM helper as data_source.password
-- (DataSourceCredentialCrypto), since a TOTP secret is just as sensitive: DB
-- access alone shouldn't be enough to generate valid login codes forever.
CREATE TABLE sys_user_totp (
  user_id BIGINT NOT NULL,
  secret_encrypted VARCHAR(255) NOT NULL,
  confirmed_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id)
);

-- 安全设置 admin page (SystemSecurityController) - view/toggle split the
-- same way every other system:* permission pair here does (system:menu:view
-- vs system:menu:update etc.), even though today only ADMIN holds either.
INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(96, 79, '安全设置', '/system/security', 'SystemSecurityPage', 'SafetyOutlined', 'system:security:view', 'MENU', 4, 'Y'),
(97, 96, '安全设置-修改', NULL, NULL, NULL, 'system:security:update', 'BUTTON', 1, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id IN (96, 97);
