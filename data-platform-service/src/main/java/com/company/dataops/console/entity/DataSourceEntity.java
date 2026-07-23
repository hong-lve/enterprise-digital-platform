package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.dataops.console.security.DataSourceCredentialTypeHandler;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * A reusable connection to a MySQL/ClickHouse/Doris/Oracle/Redis instance,
 * referenced by id from CdcSourceEntity, the SQL job sink wizard, and the
 * real-time query page - instead of each of those hand-storing/hand-typing
 * their own host/port/username/password. See V14__data_source.sql for why
 * this isn't an opaque jdbcUrl string like data-platform-service's
 * sys_data_source: Debezium needs host/port/user/password split out, not a
 * URL to reparse. REDIS is the one type that isn't JDBC-queryable (see
 * RedisConnectionService) - it still fits this same shape because Redis's
 * AUTH command accepts a username too (ACL-style, "default" if only
 * requirepass is configured with no other ACL users), so username/password
 * aren't repurposed or left unused for it.
 */
@Data
// autoResultMap = true is load-bearing, not decorative: MyBatis-Plus applies
// @TableField(typeHandler = ...) to INSERT/UPDATE parameter binding
// automatically either way, but for SELECT it only wires the handler into
// the generated ResultMap when this flag is set - without it, selectById()/
// selectList() return the raw encrypted column value as-is, no decryption,
// while writes still (successfully) encrypt. Confirmed live: every
// data_source row's password came back as raw ciphertext to callers like
// DataSourceConnectionService, and because DataSourceCredentialSeedMigrator
// re-saves every row on each startup, each restart re-encrypted that
// already-ciphertext "plaintext" on top of the last, compounding.
@TableName(value = "data_source", autoResultMap = true)
public class DataSourceEntity {
    private Long id;
    @NotBlank(message = "名称不能为空")
    private String name;
    @NotBlank(message = "类型不能为空")
    private String type;
    @NotBlank(message = "地址不能为空")
    private String host;
    @NotNull(message = "端口不能为空")
    private Integer port;
    @NotBlank(message = "用户名不能为空")
    private String username;
    // WRITE_ONLY, same reasoning/precedent as CdcSourceEntity.dbPassword -
    // never serialized back, editing leaves it blank to keep unchanged.
    // typeHandler encrypts at rest (see DataSourceCredentialTypeHandler) -
    // this field itself still holds plain text in memory, same as before.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @TableField(typeHandler = DataSourceCredentialTypeHandler.class)
    private String password;
    private String databaseName;
    // Needed to generate a SQL 流作业 sink WITH clause (executed by the Flink
    // cluster's own containers, which reach ClickHouse/Doris/Oracle via the
    // docker-compose service name, not the host/port above that this
    // service's own JVM uses) - see V14__data_source.sql's comment on the
    // "two hostnames" problem. For an Oracle data source used as a CDC
    // source, this doubles as the address Kafka Connect (also inside
    // Docker) uses for database.hostname/database.port - a dockerized
    // Oracle instance needs the same host/container split a dockerized
    // sink target does, unlike the MySQL CDC case where host.docker.internal
    // already resolves identically from both the host JVM and containers.
    private String flinkHost;
    private Integer flinkPort;
    // Doris only: FE HTTP/Stream Load port, distinct from port (FE MySQL-
    // protocol query port).
    private Integer flinkHttpPort;
    // Oracle CDB architecture only (see V20__data_source_pdb_name.sql) - the
    // pluggable database Debezium's database.pdb.name should target within
    // the CDB this data source's databaseName/host/port connect to.
    private String pdbName;
    private String status;
    private String lastTestError;
    private String environment;
    private String owner;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
