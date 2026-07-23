-- data-platform-service has no local business tables yet. Auth/menu
-- (sys_user / sys_role / sys_menu / ...) reads and writes the shared
-- platform_auth database, accessed cross-schema via entity
-- @TableName("platform_auth.xxx"). Phase 2+ tables (CDC source config,
-- Kafka topic bindings, Flink streaming job definitions) belong here.
SELECT 1;
