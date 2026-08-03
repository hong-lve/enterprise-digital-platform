package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.ContractFinding;
import com.company.dataops.dataservice.domain.ContractReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ContractGovernanceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ContractGovernanceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ContractReport save(
        long apiId,
        int versionNo,
        Integer baselineVersionNo,
        String severity,
        List<ContractFinding> findings
    ) {
        jdbcTemplate.update("""
            INSERT INTO data_service_contract_report
              (api_id, version_no, baseline_version_no, severity, findings_json)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              baseline_version_no = VALUES(baseline_version_no),
              severity = VALUES(severity),
              findings_json = VALUES(findings_json),
              generated_at = CURRENT_TIMESTAMP
            """, apiId, versionNo, baselineVersionNo, severity, write(findings));
        return find(apiId, versionNo).orElseThrow();
    }

    public Optional<ContractReport> find(long apiId, int versionNo) {
        return jdbcTemplate.query("""
            SELECT id, api_id, version_no, baseline_version_no, severity,
                   findings_json, generated_at
            FROM data_service_contract_report
            WHERE api_id = ? AND version_no = ?
            """, (rs, rowNum) -> new ContractReport(
            rs.getLong("id"),
            rs.getLong("api_id"),
            rs.getInt("version_no"),
            (Integer) rs.getObject("baseline_version_no"),
            rs.getString("severity"),
            read(rs.getString("findings_json")),
            instant(rs.getTimestamp("generated_at"))
        ), apiId, versionNo).stream().findFirst();
    }

    private String write(List<ContractFinding> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize contract findings", exception);
        }
    }

    private List<ContractFinding> read(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read contract findings", exception);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
