package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.ContractAssertion;
import com.company.dataops.dataservice.domain.ContractTestCase;
import com.company.dataops.dataservice.domain.ContractTestRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ContractTestRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ContractTestRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ContractTestCase> findCases(long apiId) {
        return jdbcTemplate.query("""
            SELECT id, api_id, name, enabled, parameters_json, page_no, page_size,
                   assertions_json, created_by, created_at, updated_at
            FROM data_service_contract_test_case
            WHERE api_id = ?
            ORDER BY id
            """, this::mapCase, apiId);
    }

    public List<ContractTestCase> findEnabledCases(long apiId) {
        return findCases(apiId).stream().filter(ContractTestCase::enabled).toList();
    }

    public Optional<ContractTestCase> findCase(long id) {
        return jdbcTemplate.query("""
            SELECT id, api_id, name, enabled, parameters_json, page_no, page_size,
                   assertions_json, created_by, created_at, updated_at
            FROM data_service_contract_test_case
            WHERE id = ?
            """, this::mapCase, id).stream().findFirst();
    }

    public ContractTestCase save(
        Long id,
        long apiId,
        String name,
        boolean enabled,
        Map<String, Object> parameters,
        int page,
        int pageSize,
        List<ContractAssertion> assertions,
        String actor
    ) {
        String parameterJson = write(parameters);
        String assertionJson = write(assertions);
        if (id == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                    INSERT INTO data_service_contract_test_case
                      (api_id, name, enabled, parameters_json, page_no, page_size,
                       assertions_json, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
                statement.setLong(1, apiId);
                statement.setString(2, name);
                statement.setBoolean(3, enabled);
                statement.setString(4, parameterJson);
                statement.setInt(5, page);
                statement.setInt(6, pageSize);
                statement.setString(7, assertionJson);
                statement.setString(8, actor);
                return statement;
            }, keyHolder);
            id = keyHolder.getKey().longValue();
        } else {
            int affected = jdbcTemplate.update("""
                UPDATE data_service_contract_test_case
                SET name = ?, enabled = ?, parameters_json = ?, page_no = ?,
                    page_size = ?, assertions_json = ?
                WHERE id = ? AND api_id = ?
                """, name, enabled, parameterJson, page, pageSize, assertionJson, id, apiId);
            if (affected != 1) {
                throw new IllegalArgumentException("Contract test case not found");
            }
        }
        return findCase(id).orElseThrow();
    }

    public ContractTestRun saveRun(
        long caseId,
        long apiId,
        int versionNo,
        String status,
        Long elapsedMs,
        Integer rowCount,
        String failureMessage,
        String actor
    ) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_contract_test_run
                  (case_id, api_id, version_no, status, elapsed_ms,
                   row_count, failure_message, run_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[]{"id"});
            statement.setLong(1, caseId);
            statement.setLong(2, apiId);
            statement.setInt(3, versionNo);
            statement.setString(4, status);
            if (elapsedMs == null) statement.setNull(5, java.sql.Types.BIGINT);
            else statement.setLong(5, elapsedMs);
            if (rowCount == null) statement.setNull(6, java.sql.Types.INTEGER);
            else statement.setInt(6, rowCount);
            statement.setString(7, truncate(failureMessage));
            statement.setString(8, actor);
            return statement;
        }, keyHolder);
        return findRun(keyHolder.getKey().longValue()).orElseThrow();
    }

    public List<ContractTestRun> findRuns(long apiId, int limit) {
        return jdbcTemplate.query("""
            SELECT id, case_id, api_id, version_no, status, elapsed_ms,
                   row_count, failure_message, run_by, run_at
            FROM data_service_contract_test_run
            WHERE api_id = ?
            ORDER BY id DESC
            LIMIT ?
            """, this::mapRun, apiId, limit);
    }

    private Optional<ContractTestRun> findRun(long id) {
        return jdbcTemplate.query("""
            SELECT id, case_id, api_id, version_no, status, elapsed_ms,
                   row_count, failure_message, run_by, run_at
            FROM data_service_contract_test_run
            WHERE id = ?
            """, this::mapRun, id).stream().findFirst();
    }

    private ContractTestCase mapCase(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ContractTestCase(
            rs.getLong("id"),
            rs.getLong("api_id"),
            rs.getString("name"),
            rs.getBoolean("enabled"),
            readMap(rs.getString("parameters_json")),
            rs.getInt("page_no"),
            rs.getInt("page_size"),
            readAssertions(rs.getString("assertions_json")),
            rs.getString("created_by"),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at"))
        );
    }

    private ContractTestRun mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ContractTestRun(
            rs.getLong("id"),
            rs.getLong("case_id"),
            rs.getLong("api_id"),
            rs.getInt("version_no"),
            rs.getString("status"),
            (Long) rs.getObject("elapsed_ms"),
            (Integer) rs.getObject("row_count"),
            rs.getString("failure_message"),
            rs.getString("run_by"),
            instant(rs.getTimestamp("run_at"))
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize contract test", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read test parameters", exception);
        }
    }

    private List<ContractAssertion> readAssertions(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read test assertions", exception);
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
