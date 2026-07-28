-- EnvironmentGuard already requires realtime:env:prod-operate to touch a
-- PROD-tagged resource, but that permission alone still lets one person
-- unilaterally delete/stop a live production CDC source or Flink job with
-- no second pair of eyes. This adds a maker-checker gate on top: ChangeApprovalService
-- only defers to this table when the target resource's environment is PROD
-- (DEV stays exactly as immediate as it's always been), and a request can't
-- be approved/rejected by the same user who filed it.
CREATE TABLE change_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  action_type VARCHAR(50) NOT NULL,
  target_id BIGINT NOT NULL,
  target_summary VARCHAR(255),
  requester VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  approver VARCHAR(100),
  reject_reason VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at DATETIME,
  INDEX idx_change_request_status (status),
  INDEX idx_change_request_requester (requester)
);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(108, 79, '审批中心', '/system/approval-center', 'ApprovalCenterPage', 'SafetyCertificateOutlined', 'system:approval:view', 'MENU', 6, 'Y'),
(109, 108, '审批中心-处理', NULL, NULL, NULL, 'system:approval:handle', 'BUTTON', 1, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id >= 108;

-- Only one real account (admin) exists in this checkout, but maker-checker
-- is meaningless with a single user - requireNotSelfApproval() blocks a
-- requester from approving their own request, so verifying that live needs
-- a genuinely separate second account. Same ADMIN role as admin (this
-- platform only has the one role today); password is 'approver123' (bcrypt,
-- generated the same way as admin's V22 hash).
INSERT INTO sys_user (username, display_name, email, password_hash, status) VALUES
('approver', '审批人（演示账号）', 'approver@example.com', '$2a$10$TyoIOK1ZhRxb0iqN7JN.ke/M0MdLZNMKbE4f2zbomCuwyKPoJE/se', 'ENABLED');

INSERT INTO sys_user_role (user_id, role_id)
SELECT id, 1 FROM sys_user WHERE username = 'approver';
