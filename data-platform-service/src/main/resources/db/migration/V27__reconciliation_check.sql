-- Backpressure/consumer-lag/connector-failure alerts (alert_history) all
-- catch "something is obviously broken", but none of them catch "the CDC
-- pipeline is quietly running with a gap" - a message dropped mid-stream,
-- a sink write that silently failed and got skipped, a connector that
-- resumed from the wrong offset after a restart. Those all look identical
-- to "everything's fine" from the connector-status/lag-freshness angle
-- alone: the only way to actually catch them is comparing how many rows
-- ended up on each side.
CREATE TABLE reconciliation_check (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  source_data_source_id BIGINT NOT NULL,
  source_database VARCHAR(200),
  source_table VARCHAR(200) NOT NULL,
  target_data_source_id BIGINT NOT NULL,
  target_database VARCHAR(200),
  -- For a JDBC target (ClickHouse/MySQL/Oracle/Doris) this is the table
  -- name; for a Redis target there's no table concept, so it holds the key
  -- pattern (e.g. "test_orders_mysql_redis_sink:*") counted via SCAN
  -- instead of COUNT(*) - see DataReconciliationService.
  target_table VARCHAR(200) NOT NULL,
  tolerance INT NOT NULL DEFAULT 0 COMMENT '容忍的行数差异，CDC 异步复制通常有短暂的滞后',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  last_source_count BIGINT,
  last_target_count BIGINT,
  last_checked_at DATETIME,
  last_state VARCHAR(10) DEFAULT 'OK' COMMENT 'OK/DRIFT/ERROR',
  last_error VARCHAR(500),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(102, 40, '数据对账', '/realtime/reconciliation', 'ReconciliationPage', 'DiffOutlined', 'realtime:reconciliation:view', 'MENU', 51, 'Y'),
(103, 102, '数据对账-新建', NULL, NULL, NULL, 'realtime:reconciliation:create', 'BUTTON', 1, 'Y'),
(104, 102, '数据对账-编辑', NULL, NULL, NULL, 'realtime:reconciliation:update', 'BUTTON', 2, 'Y'),
(105, 102, '数据对账-删除', NULL, NULL, NULL, 'realtime:reconciliation:delete', 'BUTTON', 3, 'Y'),
(106, 102, '数据对账-立即执行', NULL, NULL, NULL, 'realtime:reconciliation:run', 'BUTTON', 4, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id >= 102;
