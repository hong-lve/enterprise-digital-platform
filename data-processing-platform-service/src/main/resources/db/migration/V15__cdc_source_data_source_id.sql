-- CDC 数据源不再自己存一份连接信息（db_host/db_port/db_username/db_password），
-- 改成引用 data_source 表的一行 - 这样同一个 MySQL 实例可以被多个 CDC 源复用，
-- 数据源本身也能在"数据源配置"页面统一管理/测试连接。database_name（Debezium
-- 要监听哪个库）留在 cdc_source 上不动，跟 data_source.database_name（这个
-- 实例的默认库）是两个概念 - 同一个 MySQL 实例下，不同 CDC 源可能监听不同库。
ALTER TABLE cdc_source ADD COLUMN data_source_id BIGINT NULL;

-- 把每一条现有 CDC 源自己的连接信息迁移成一条新的 data_source 行，避免迁移后
-- 现有 CDC 源失联；行的 name 里带上源 id 防止唯一性以外的裸 name 冲突，仅用于
-- 本次迁移内部关联，迁移完成后可以在数据源配置页面里重命名成任何名字。
INSERT INTO data_source (name, type, host, port, username, password, database_name, environment, owner)
SELECT CONCAT('cdc-source-', id, '-', name), 'MYSQL', db_host, db_port, db_username, db_password, database_name, environment, owner
FROM cdc_source;

UPDATE cdc_source cs
JOIN data_source ds ON ds.name = CONCAT('cdc-source-', cs.id, '-', cs.name) AND ds.type = 'MYSQL'
SET cs.data_source_id = ds.id;

ALTER TABLE cdc_source MODIFY COLUMN data_source_id BIGINT NOT NULL;
ALTER TABLE cdc_source DROP COLUMN db_host, DROP COLUMN db_port, DROP COLUMN db_username, DROP COLUMN db_password;
