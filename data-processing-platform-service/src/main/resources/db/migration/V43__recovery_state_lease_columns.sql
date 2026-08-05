-- RecoveryStateEntity/RecoveryStateMapper 从 RecoveryOrchestrator 上线起就一直在
-- 读写 recovery_state.lease_owner / lease_until（acquireLease/renewLease/
-- releaseLease/reset 四个语句全都引用），但这两列从来没有出现在任何迁移里：
-- V34 建表时没有，V40 只 MODIFY 了 entity_type，V41 建的 platform_lease 是另一张
-- 独立的表。之前的环境之所以能跑，是因为当时直接在库上手工 ALTER 补了列、没有回写
-- 成迁移脚本 —— 于是这个缺口只在"全新库"上才暴露：2026-08-05 在新服务器上从空库
-- 跑完 V1→V42 后，启动 CDC 数据源立刻 500，报
-- `Unknown column 'lease_owner' in 'field list'`。
--
-- 用 DATETIME（非 DATETIME(3)）跟同一批引入的 alert_retry_queue.lock_until 保持一致；
-- mapper 里是 `TIMESTAMPADD(SECOND, ...)` / `lease_until < NOW()`，秒级精度就够。
-- 不加索引：acquireLease/releaseLease 都是按主键 id 定位的。
ALTER TABLE recovery_state
  ADD COLUMN lease_owner VARCHAR(64) NULL COMMENT '持有恢复租约的实例标识，NULL 表示空闲' AFTER circuit_state,
  ADD COLUMN lease_until DATETIME NULL COMMENT '租约到期时间，过期后其他实例可抢占' AFTER lease_owner;
