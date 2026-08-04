package com.company.dataops.console.service.flink;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LegacyFlinkSqlCredentialMigrator implements ApplicationRunner {
    private final FlinkSqlJobMapper jobMapper;
    private final DataSourceMapper dataSourceMapper;
    private final FlinkSqlSecretResolver secretResolver;

    public LegacyFlinkSqlCredentialMigrator(FlinkSqlJobMapper jobMapper, DataSourceMapper dataSourceMapper,
                                             FlinkSqlSecretResolver secretResolver) {
        this.jobMapper = jobMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.secretResolver = secretResolver;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DataSourceEntity> dataSources = dataSourceMapper.selectList(null);
        for (FlinkSqlJobEntity job : jobMapper.selectList(null)) {
            String migrated = job.getSqlScript();
            for (DataSourceEntity dataSource : dataSources) {
                if (dataSource.getPassword() == null || dataSource.getPassword().isEmpty()) {
                    continue;
                }
                String literal = dataSource.getPassword().replace("'", "''");
                migrated = migrated.replaceAll("(?i)('password'\\s*=\\s*)'" + java.util.regex.Pattern.quote(literal) + "'",
                    "$1'" + java.util.regex.Matcher.quoteReplacement(secretResolver.reference(dataSource.getId())) + "'");
            }
            if (!migrated.equals(job.getSqlScript())) {
                jobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                    .eq(FlinkSqlJobEntity::getId, job.getId())
                    .set(FlinkSqlJobEntity::getSqlScript, migrated));
            }
        }
    }
}
