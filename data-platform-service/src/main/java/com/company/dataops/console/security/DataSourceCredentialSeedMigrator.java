package com.company.dataops.console.security;

import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-time upgrade path for rows written before DataSourceCredentialTypeHandler
 * existed (V14's seed data, anything registered before this change shipped):
 * their password column still holds plain text, which
 * DataSourceCredentialTypeHandler.decrypt() falls back to returning as-is
 * rather than erroring on. Re-saving every row here forces
 * setNonNullParameter() to run once more, which always encrypts - so
 * whatever comes back out of the read is now written back through the
 * encrypting path. Safe to run on every startup: already-encrypted rows just
 * get re-encrypted with a fresh IV, which changes the stored bytes but not
 * the decrypted value.
 */
@Component
public class DataSourceCredentialSeedMigrator implements ApplicationRunner {
    private final DataSourceMapper dataSourceMapper;

    public DataSourceCredentialSeedMigrator(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (DataSourceEntity dataSource : dataSourceMapper.selectList(null)) {
            dataSourceMapper.updateById(dataSource);
        }
    }
}
