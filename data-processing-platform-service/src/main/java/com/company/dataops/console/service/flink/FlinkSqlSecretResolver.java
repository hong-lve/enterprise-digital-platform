package com.company.dataops.console.service.flink;

import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FlinkSqlSecretResolver {
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{secret:datasource:(\\d+):password}");
    private static final Pattern PASSWORD_OPTION = Pattern.compile("(?i)'password'\\s*=\\s*'([^']*)'");
    private final DataSourceMapper dataSourceMapper;

    public FlinkSqlSecretResolver(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    public String resolveForSubmission(String sql) {
        Matcher matcher = REFERENCE.matcher(sql);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            Long dataSourceId = Long.valueOf(matcher.group(1));
            DataSourceEntity dataSource = dataSourceMapper.selectById(dataSourceId);
            if (dataSource == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL 引用的数据源不存在：" + dataSourceId);
            }
            String password = dataSource.getPassword() == null ? "" : dataSource.getPassword();
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(escapeSqlLiteral(password)));
        }
        matcher.appendTail(resolved);
        if (REFERENCE.matcher(resolved).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL 中存在无法解析的凭据引用");
        }
        return resolved.toString();
    }

    public void requireReferencesOnly(String sql) {
        Matcher matcher = PASSWORD_OPTION.matcher(sql);
        while (matcher.find()) {
            if (!REFERENCE.matcher(matcher.group(1)).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SQL 禁止保存明文密码，请使用 ${secret:datasource:<id>:password} 引用");
            }
        }
    }

    public String reference(Long dataSourceId) {
        return "${secret:datasource:" + dataSourceId + ":password}";
    }

    private String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }
}
