-- Oracle CDB architecture only: databaseName already holds the actual JDBC
-- connection target for a data source (the CDB root service name, e.g.
-- "FREE", for a CDC-purposed Oracle connection - LogMiner reads redo logs
-- shared across the whole CDB, so the connection itself targets the root,
-- not a PDB). pdb_name is the separate pluggable database Debezium should
-- actually monitor within that CDB (e.g. "FREEPDB1"), fed to Debezium's
-- database.pdb.name property - confirmed required live, Debezium's Oracle
-- connector needs both to resolve a multitenant target.
ALTER TABLE data_source ADD COLUMN pdb_name VARCHAR(100) NULL COMMENT 'Oracle CDB only - the pluggable database Debezium should monitor within the CDB this data source connects to';
