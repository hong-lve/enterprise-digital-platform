package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.dataops.dataservice.domain.ApiParameter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SqlSecurityPolicyTest {
    private SqlSecurityPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SqlSecurityPolicy(1, 0, true);
    }

    @Test
    void acceptsSingleTableSelectWithDeclaredParameter() {
        assertDoesNotThrow(() -> policy.validate(
            "SELECT id, api_path FROM data_service_call_log WHERE api_path = :apiPath",
            "data_service_call_log",
            List.of(parameter("apiPath"))
        ));
    }

    @Test
    void rejectsMultipleStatements() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            "SELECT id FROM data_service_call_log; DELETE FROM data_service_call_log",
            "data_service_call_log",
            List.of()
        ));
    }

    @Test
    void rejectsSelectStar() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            "SELECT * FROM data_service_call_log",
            "data_service_call_log",
            List.of()
        ));
    }

    @Test
    void rejectsJoinEvenWhenDatasetTableIsReferenced() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            """
            SELECT log.id
            FROM data_service_call_log log
            JOIN data_service_api api ON api.id = log.api_id
            """,
            "data_service_call_log",
            List.of()
        ));
    }

    @Test
    void rejectsUnionQuery() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            """
            SELECT id FROM data_service_call_log
            UNION ALL
            SELECT id FROM data_service_call_log
            """,
            "data_service_call_log",
            List.of()
        ));
    }

    @Test
    void rejectsCommonTableExpressionAndRepeatedNestedRead() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            """
            WITH recent_logs AS (
              SELECT id FROM data_service_call_log
            )
            SELECT id FROM recent_logs
            """,
            "data_service_call_log",
            List.of()
        ));
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            """
            SELECT id
            FROM data_service_call_log
            WHERE id IN (SELECT id FROM data_service_call_log)
            """,
            "data_service_call_log",
            List.of()
        ));
    }

    @Test
    void rejectsBlockedFunctionButIgnoresFunctionNameInsideStringLiteral() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            "SELECT id, SLEEP(3) AS wait_time FROM data_service_call_log",
            "data_service_call_log",
            List.of()
        ));
        assertDoesNotThrow(() -> policy.validate(
            "SELECT id, 'SLEEP(3)' AS example_text FROM data_service_call_log",
            "data_service_call_log",
            List.of()
        ));
    }

    @Test
    void rejectsCallerControlledPaginationAndRowLocks() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            "SELECT id FROM data_service_call_log LIMIT 100",
            "data_service_call_log",
            List.of()
        ));
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            "SELECT id FROM data_service_call_log FOR UPDATE",
            "data_service_call_log",
            List.of()
        ));
    }

    @Test
    void injectsTrustedRowFilterWithoutChangingExistingOrPrecedence() {
        String secured = policy.secureAndValidate(
            """
            SELECT id, app_key
            FROM data_service_call_log
            WHERE status_code = 200 OR status_code = 201
            """,
            "data_service_call_log",
            List.of(),
            "app_key = :_appKey"
        );
        org.junit.jupiter.api.Assertions.assertTrue(
            secured.contains("(status_code = 200 OR status_code = 201)")
        );
        org.junit.jupiter.api.Assertions.assertTrue(secured.contains("app_key = :_appKey"));
    }

    @Test
    void rejectsUntrustedRowFilterParameterAndSubquery() {
        assertThrows(ResponseStatusException.class, () ->
            policy.validateRowFilter("tenant_id = :tenantId"));
        assertThrows(ResponseStatusException.class, () ->
            policy.validateRowFilter("id IN (SELECT id FROM other_table)"));
    }

    @Test
    void rejectsUnusedParameterDefinition() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            "SELECT id FROM data_service_call_log",
            "data_service_call_log",
            List.of(parameter("apiPath"))
        ));
    }

    @Test
    void rejectsParameterNameInsideStringLiteralAsUsage() {
        assertThrows(ResponseStatusException.class, () -> policy.validate(
            "SELECT id, ':apiPath' AS sample FROM data_service_call_log",
            "data_service_call_log",
            List.of(parameter("apiPath"))
        ));
    }

    private ApiParameter parameter(String name) {
        return new ApiParameter(name, "QUERY", "STRING", true, null, null);
    }
}
