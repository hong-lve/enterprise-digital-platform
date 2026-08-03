-- Schema evolution / data contract (tier 2 item 1 of the reliability
-- roadmap): tracks the last Schema Registry version SchemaDriftScheduler has
-- seen for each (cdc_source, subject) pair, so it can tell "a new schema
-- version just appeared" apart from "nothing changed since the last poll" -
-- Schema Registry itself has no push/webhook mechanism, only a version list
-- to diff against what was seen last time.
CREATE TABLE schema_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cdc_source_id BIGINT NOT NULL,
  subject VARCHAR(300) NOT NULL,
  last_seen_version INT NOT NULL,
  last_compatibility_result VARCHAR(20) NULL COMMENT 'COMPATIBLE or INCOMPATIBLE, from the last time a version bump was checked',
  checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_schema_snapshot_subject (cdc_source_id, subject)
);

INSERT INTO sys_menu (id, parent_id, title, path, component, icon, permission, type, sort_order, visible) VALUES
(114, 42, 'CDC 数据源-Schema 管理', NULL, NULL, NULL, 'realtime:cdc:schema-manage', 'BUTTON', 7, 'Y');

INSERT INTO sys_role_menu (role_id, menu_id) VALUES (1, 114);
