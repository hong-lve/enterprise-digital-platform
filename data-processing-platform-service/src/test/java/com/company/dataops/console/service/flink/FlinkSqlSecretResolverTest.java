package com.company.dataops.console.service.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FlinkSqlSecretResolverTest {
    private final DataSourceMapper mapper = mock(DataSourceMapper.class);
    private final FlinkSqlSecretResolver resolver = new FlinkSqlSecretResolver(mapper);

    @Test
    void materializesPasswordOnlyAtSubmissionAndEscapesSqlLiteral() {
        DataSourceEntity dataSource = new DataSourceEntity();
        dataSource.setPassword("p'ass");
        when(mapper.selectById(12L)).thenReturn(dataSource);

        assertEquals("WITH ('password'='p''ass')",
            resolver.resolveForSubmission("WITH ('password'='${secret:datasource:12:password}')"));
    }

    @Test
    void rejectsPersistingPlaintextPassword() {
        assertThrows(ResponseStatusException.class,
            () -> resolver.requireReferencesOnly("WITH ('password'='plain-secret')"));
    }
}
