-- Tier 3 item 3 of the reliability roadmap ("告警重试队列、告警升级、值班人和静默窗口").
-- RealtimeAlertService.send()/notifyMultiple() previously: fired a webhook
-- unconditionally and gave up silently on failure (only a WARN log), always
-- delivered to a single fixed "owner" with no concept of who's actually on
-- duty right now, and had no way to suppress notifications during planned
-- maintenance without either deleting the resource or eating the noise.

-- 值班排班：显式班次记录（不做循环规则引擎，保持和这个项目其余功能一致的
-- 从简原则）- OnCallService.currentOnCall() 查询覆盖当前时间的班次。
CREATE TABLE on_call_schedule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL,
  note VARCHAR(200) NULL,
  created_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_on_call_schedule_window (starts_at, ends_at)
);

-- 静默窗口：在维护期间抑制 webhook/站内信投递 - alert_history 仍然照常记录
-- （审计时间线要完整），只是不打扰人。entity_type/entity_id 为空表示范围
-- 更广（entity_type 非空+entity_id 为空=该类型全体，两者都空=全局）。
CREATE TABLE alert_silence_window (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  entity_type VARCHAR(20) NULL,
  entity_id BIGINT NULL,
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL,
  reason VARCHAR(200) NULL,
  created_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_alert_silence_window_window (starts_at, ends_at)
);

-- 告警投递重试队列：webhook 发送失败时不再只打一条 WARN 日志就放弃，而是入队，
-- 由 AlertRetryScheduler 按退避策略（30s/120s/600s）重试，达到 max_attempts
-- 仍失败则标记 FAILED，留痕供人工排查。
CREATE TABLE alert_retry_queue (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(300) NOT NULL,
  content VARCHAR(1000) NULL,
  type VARCHAR(20) NOT NULL,
  link_url VARCHAR(500) NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  next_attempt_at DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCEEDED/FAILED',
  last_error VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_alert_retry_queue_due (status, next_attempt_at)
);

-- 告警升级：某个实体持续 ALERTING 超过阈值时间未恢复时，AlertEscalationScheduler
-- 会再次通知当前值班人一次，并把这一行标记 escalated 避免每次轮询都重复升级
-- 通知 - 下一次真正的状态变化（RECOVERY 或新的 ALERTING）会写入新的一行，
-- 天然带着 escalated=0，所以不需要手动清除这个字段。
ALTER TABLE alert_history ADD COLUMN escalated TINYINT(1) NOT NULL DEFAULT 0;

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(120, 40, '值班与静默', '/realtime/oncall', 'AlertOpsPage', 'TeamOutlined', 'realtime:oncall:view', 'MENU', 53, 'Y'),
(121, 120, '值班与静默-管理', NULL, NULL, NULL, 'realtime:oncall:manage', 'BUTTON', 1, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id >= 120;
