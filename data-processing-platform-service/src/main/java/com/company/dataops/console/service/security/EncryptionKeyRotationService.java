package com.company.dataops.console.service.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.entity.EncryptionKeyRotationEventEntity;
import com.company.dataops.console.entity.UserTotpEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.mapper.EncryptionKeyRotationEventMapper;
import com.company.dataops.console.mapper.UserTotpMapper;
import com.company.dataops.console.security.DataSourceCredentialCrypto;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Tier 3 item 4 of the reliability roadmap ("密钥版本化及轮换") - the
 * key material itself only ever lives in config/env vars (see
 * DataSourceCredentialCrypto), so "rotation" here means re-encrypting every
 * already-stored secret under whichever version is currently configured,
 * not generating or storing new key bytes anywhere.
 *
 * data_source rows already get this treatment on every backend restart (see
 * DataSourceCredentialSeedMigrator, which re-saves every row at startup) -
 * rotate() just triggers the same re-save on demand, without a restart.
 * sys_user_totp rows have no equivalent startup migrator (TOTP secrets are
 * encrypted/decrypted via direct static calls, not a MyBatis typeHandler -
 * see AuthController), so this is the only path that ever rotates them.
 */
@Component
public class EncryptionKeyRotationService {
    private final DataSourceMapper dataSourceMapper;
    private final UserTotpMapper userTotpMapper;
    private final EncryptionKeyRotationEventMapper rotationEventMapper;

    public EncryptionKeyRotationService(
        DataSourceMapper dataSourceMapper,
        UserTotpMapper userTotpMapper,
        EncryptionKeyRotationEventMapper rotationEventMapper
    ) {
        this.dataSourceMapper = dataSourceMapper;
        this.userTotpMapper = userTotpMapper;
        this.rotationEventMapper = rotationEventMapper;
    }

    public StatusView status() {
        Map<String, Integer> dataSourceVersionCounts = new HashMap<>();
        for (Map<String, Object> row : dataSourceMapper.selectRawIdAndPassword()) {
            String version = DataSourceCredentialCrypto.detectVersion((String) row.get("password"));
            dataSourceVersionCounts.merge(version, 1, Integer::sum);
        }
        Map<String, Integer> totpVersionCounts = new HashMap<>();
        for (UserTotpEntity totp : userTotpMapper.selectList(null)) {
            String version = DataSourceCredentialCrypto.detectVersion(totp.getSecretEncrypted());
            totpVersionCounts.merge(version, 1, Integer::sum);
        }
        List<EncryptionKeyRotationEventEntity> recentEvents = rotationEventMapper.selectList(new LambdaQueryWrapper<EncryptionKeyRotationEventEntity>()
            .orderByDesc(EncryptionKeyRotationEventEntity::getId)
            .last("LIMIT 20"));
        return new StatusView(
            DataSourceCredentialCrypto.currentVersion(),
            DataSourceCredentialCrypto.configuredVersions(),
            dataSourceVersionCounts,
            totpVersionCounts,
            recentEvents
        );
    }

    /** Re-encrypts every data_source and sys_user_totp row under the currently-configured key version. */
    public EncryptionKeyRotationEventEntity rotate(String triggeredBy) {
        EncryptionKeyRotationEventEntity event = new EncryptionKeyRotationEventEntity();
        event.setToVersion(DataSourceCredentialCrypto.currentVersion());
        event.setStatus("RUNNING");
        event.setTriggeredBy(triggeredBy);
        event.setStartedAt(LocalDateTime.now());
        event.setDataSourceRowsReencrypted(0);
        event.setTotpRowsReencrypted(0);
        rotationEventMapper.insert(event);

        try {
            int dataSourceCount = 0;
            // Each row is already decrypted in memory by DataSourceEntity's
            // typeHandler-backed select - updateById() forces a fresh
            // setNonNullParameter() call on write, which always re-encrypts
            // under whatever version is CURRENTLY configured, same mechanism
            // DataSourceCredentialSeedMigrator already relies on at startup.
            for (DataSourceEntity dataSource : dataSourceMapper.selectList(null)) {
                dataSourceMapper.updateById(dataSource);
                dataSourceCount++;
            }

            int totpCount = 0;
            for (UserTotpEntity totp : userTotpMapper.selectList(null)) {
                String plaintext = DataSourceCredentialCrypto.tryDecrypt(totp.getSecretEncrypted());
                if (plaintext == null) {
                    // Can't decrypt under any configured key - skip rather
                    // than destroy a secret we can't recover, same
                    // fail-safe posture as the crypto class's own tryDecrypt().
                    continue;
                }
                totp.setSecretEncrypted(DataSourceCredentialCrypto.encrypt(plaintext));
                userTotpMapper.updateById(totp);
                totpCount++;
            }

            event.setDataSourceRowsReencrypted(dataSourceCount);
            event.setTotpRowsReencrypted(totpCount);
            event.setStatus("SUCCEEDED");
            event.setFinishedAt(LocalDateTime.now());
            rotationEventMapper.updateById(event);
            return event;
        } catch (Exception exception) {
            event.setStatus("FAILED");
            event.setErrorMessage(exception.getMessage());
            event.setFinishedAt(LocalDateTime.now());
            rotationEventMapper.updateById(event);
            throw exception;
        }
    }

    public record StatusView(
        String currentVersion,
        Set<String> configuredVersions,
        Map<String, Integer> dataSourceRowsByVersion,
        Map<String, Integer> totpRowsByVersion,
        List<EncryptionKeyRotationEventEntity> recentEvents
    ) {
    }
}
