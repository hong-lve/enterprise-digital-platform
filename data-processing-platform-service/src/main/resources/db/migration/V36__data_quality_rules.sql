-- Tier 2 item 2 of the reliability roadmap ("数据质量规则引擎"):
-- DataReconciliationService was purely row-count based - can't tell "same
-- row count, wrong values" apart from "actually fine", can't tell WHICH
-- partition/day drifted when a table spans many, and has no concept of a
-- single table's own data quality (nulls/duplicates/staleness/out-of-range
-- values) independent of any source/target comparison at all.

-- AGGREGATE check type (source vs target SUM(column) comparison, catches
-- value drift that a plain COUNT(*) match completely misses) and an
-- optional partition_column (breaks either check type into a per-partition
-- GROUP BY comparison instead of one table-wide number, so a drift can be
-- pinned to which partition/day it's actually in) - both apply to the
-- existing ROW_COUNT type too, not just AGGREGATE.
ALTER TABLE reconciliation_check
  ADD COLUMN check_type VARCHAR(20) NOT NULL DEFAULT 'ROW_COUNT' COMMENT 'ROW_COUNT or AGGREGATE',
  ADD COLUMN aggregate_column VARCHAR(200) NULL COMMENT 'numeric column to SUM and compare - required when check_type=AGGREGATE',
  ADD COLUMN partition_column VARCHAR(200) NULL COMMENT 'optional - breaks the comparison into a per-partition-value GROUP BY instead of one table-wide number',
  ADD COLUMN last_source_aggregate DOUBLE NULL,
  ADD COLUMN last_target_aggregate DOUBLE NULL,
  ADD COLUMN partition_drift_summary VARCHAR(1000) NULL COMMENT 'human-readable list of which partition values drifted, when partition_column is set';

-- Single-table quality rules - unlike reconciliation_check, these don't
-- compare source vs target, they check one table's own data against a
-- threshold (数据新鲜度/空值率/唯一率/值域/主键重复).
CREATE TABLE data_quality_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(200) NOT NULL,
  data_source_id BIGINT NOT NULL,
  database_name VARCHAR(200),
  table_name VARCHAR(200) NOT NULL,
  rule_type VARCHAR(20) NOT NULL COMMENT 'NULL_RATE, UNIQUENESS, VALUE_RANGE, PK_DUPLICATE, or FRESHNESS',
  -- The column being checked - for PK_DUPLICATE the primary key column, for
  -- FRESHNESS a timestamp column, for the others the column whose null
  -- rate/distinctness/range is being measured.
  column_name VARCHAR(200) NOT NULL,
  threshold_min DOUBLE NULL COMMENT 'VALUE_RANGE only: minimum allowed value',
  threshold_max DOUBLE NULL COMMENT 'VALUE_RANGE max, or the max allowed null-rate/duplicate-rate fraction (0-1), or max allowed staleness in seconds for FRESHNESS',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  last_result VARCHAR(10) DEFAULT 'OK' COMMENT 'OK/VIOLATION/ERROR',
  last_metric_value DOUBLE NULL COMMENT 'the actual measured value (null rate, staleness seconds, etc.) from the last run',
  last_violation_count INT NULL COMMENT 'for PK_DUPLICATE/VALUE_RANGE: how many offending rows were found',
  last_checked_at DATETIME,
  last_error VARCHAR(500),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- The "异常数据进入 DLQ 或隔离表" requirement - not a full DLQ (this
-- platform has no message-bus-level dead-letter concept for a JDBC data
-- source), but the concrete offending values PK_DUPLICATE/VALUE_RANGE find,
-- so a human has something to actually go inspect rather than just a count.
-- Cleared and rewritten fresh each run - see DataQualityRuleService.
CREATE TABLE data_quality_violation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id BIGINT NOT NULL,
  row_identifier VARCHAR(500) NOT NULL COMMENT 'the offending column value (a duplicated PK, or a value outside range)',
  detail VARCHAR(500) NULL,
  detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_data_quality_violation_rule (rule_id)
);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(115, 40, '数据质量规则', '/realtime/data-quality', 'DataQualityRulesPage', 'SafetyOutlined', 'realtime:data-quality:view', 'MENU', 52, 'Y'),
(116, 115, '数据质量规则-新建', NULL, NULL, NULL, 'realtime:data-quality:create', 'BUTTON', 1, 'Y'),
(117, 115, '数据质量规则-编辑', NULL, NULL, NULL, 'realtime:data-quality:update', 'BUTTON', 2, 'Y'),
(118, 115, '数据质量规则-删除', NULL, NULL, NULL, 'realtime:data-quality:delete', 'BUTTON', 3, 'Y'),
(119, 115, '数据质量规则-立即执行', NULL, NULL, NULL, 'realtime:data-quality:run', 'BUTTON', 4, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id >= 115;
