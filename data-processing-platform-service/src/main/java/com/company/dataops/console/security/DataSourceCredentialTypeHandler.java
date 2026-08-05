package com.company.dataops.console.security;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * Wired onto DataSourceEntity.password via @TableField(typeHandler = ...) -
 * every existing call site that reads dataSource.getPassword() to actually
 * connect (DataSourceConnectionService, DebeziumConnectorConfigBuilder,
 * SinkTableDdlBuilder, CdcTableSchemaService) keeps
 * working unchanged: the entity still holds plain text in memory, only what
 * MyBatis sends to/reads from the password column is ciphertext.
 */
@MappedTypes(String.class)
public class DataSourceCredentialTypeHandler extends BaseTypeHandler<String> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, DataSourceCredentialCrypto.encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex));
    }

    // Rows written before this type handler existed still hold plain text -
    // tryDecrypt() returns null for those (not validly encrypted data), so
    // fall back to the raw stored value rather than surfacing a decrypt
    // error. DataSourceCredentialSeedMigrator re-saves every row once at
    // startup, which re-encrypts anything still in this state.
    private String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        String plaintext = DataSourceCredentialCrypto.tryDecrypt(stored);
        return plaintext != null ? plaintext : stored;
    }
}
