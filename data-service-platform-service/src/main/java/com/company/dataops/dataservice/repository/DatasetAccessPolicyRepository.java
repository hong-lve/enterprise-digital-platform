package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.DatasetAccessPolicy;
import com.company.dataops.dataservice.domain.DatasetColumnPolicy;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DatasetAccessPolicyRepository {
    private final JdbcTemplate jdbcTemplate;

    public DatasetAccessPolicyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DatasetAccessPolicy findByDatasetId(long datasetId) {
        List<PolicyHeader> headers = jdbcTemplate.query("""
            SELECT row_filter_sql, updated_by, updated_at
            FROM data_service_dataset_policy
            WHERE dataset_id = ?
            """, (rs, rowNum) -> new PolicyHeader(
            rs.getString("row_filter_sql"),
            rs.getString("updated_by"),
            toInstant(rs.getTimestamp("updated_at"))
        ), datasetId);

        List<DatasetColumnPolicy> columns = jdbcTemplate.query("""
            SELECT column_name, action, mask_type
            FROM data_service_dataset_column_policy
            WHERE dataset_id = ?
            ORDER BY column_name
            """, (rs, rowNum) -> new DatasetColumnPolicy(
            rs.getString("column_name"),
            rs.getString("action"),
            rs.getString("mask_type")
        ), datasetId);

        PolicyHeader header = headers.isEmpty() ? null : headers.get(0);
        return new DatasetAccessPolicy(
            datasetId,
            header == null ? null : header.rowFilterSql(),
            columns,
            header == null ? null : header.updatedBy(),
            header == null ? null : header.updatedAt()
        );
    }

    @Transactional
    public DatasetAccessPolicy replace(
        long datasetId,
        String rowFilterSql,
        List<DatasetColumnPolicy> columns,
        String actor
    ) {
        jdbcTemplate.update("""
            INSERT INTO data_service_dataset_policy (dataset_id, row_filter_sql, updated_by)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE
              row_filter_sql = VALUES(row_filter_sql),
              updated_by = VALUES(updated_by),
              updated_at = CURRENT_TIMESTAMP
            """, datasetId, blankToNull(rowFilterSql), actor);
        jdbcTemplate.update(
            "DELETE FROM data_service_dataset_column_policy WHERE dataset_id = ?",
            datasetId
        );
        for (DatasetColumnPolicy column : columns) {
            jdbcTemplate.update("""
                INSERT INTO data_service_dataset_column_policy
                  (dataset_id, column_name, action, mask_type)
                VALUES (?, ?, ?, ?)
                """,
                datasetId,
                column.columnName(),
                column.action(),
                column.maskType()
            );
        }
        return findByDatasetId(datasetId);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record PolicyHeader(String rowFilterSql, String updatedBy, java.time.Instant updatedAt) {
    }
}
