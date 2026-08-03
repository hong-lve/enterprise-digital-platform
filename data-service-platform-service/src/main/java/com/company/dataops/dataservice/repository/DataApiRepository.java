package com.company.dataops.dataservice.repository;

import com.company.dataops.dataservice.domain.ApiParameter;
import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.service.ApiLifecyclePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class DataApiRepository {
    private static final String SELECT_COLUMNS = """
        SELECT api.id, api.dataset_id, api.name, api.description, api.path, api.method,
               api.query_sql, api.parameters_json, api.status, api.version,
               (SELECT latest.status
                  FROM data_service_api_version latest
                 WHERE latest.api_id = api.id
                 ORDER BY latest.version_no DESC LIMIT 1) AS latest_version_status,
               published.version_no AS published_version,
               api.cache_ttl_seconds, api.max_page_size, api.published_at,
               api.created_at, api.updated_at
        FROM data_service_api api
        LEFT JOIN data_service_api_version published ON published.id = api.published_version_id
        """;

    private static final String PUBLISHED_COLUMNS = """
        SELECT api.id, published.dataset_id, published.name, published.description,
               published.path, published.method, published.query_sql, published.parameters_json,
               api.status, published.version_no AS version,
               published.status AS latest_version_status,
               published.version_no AS published_version,
               published.cache_ttl_seconds, published.max_page_size, published.published_at,
               api.created_at, api.updated_at
        FROM data_service_api api
        JOIN data_service_api_version published ON published.id = api.published_version_id
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApiLifecyclePolicy lifecyclePolicy;
    private final RowMapper<DataApiRecord> rowMapper = this::map;
    private final RowMapper<ApiVersionRecord> versionRowMapper = this::mapVersion;

    public DataApiRepository(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        ApiLifecyclePolicy lifecyclePolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.lifecyclePolicy = lifecyclePolicy;
    }

    public List<DataApiRecord> findAll() {
        return jdbcTemplate.query(SELECT_COLUMNS + " ORDER BY api.id DESC", rowMapper);
    }

    public Optional<DataApiRecord> findById(long id) {
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE api.id = ?", rowMapper, id)
            .stream().findFirst();
    }

    public Optional<DataApiRecord> findPublished(String path, String method) {
        return jdbcTemplate.query(
            PUBLISHED_COLUMNS + """
                WHERE published.path = ? AND published.method = ? AND api.status = 'PUBLISHED'
                """,
            rowMapper,
            path,
            method
        ).stream().findFirst();
    }

    public List<DataApiRecord> findPublishedAll() {
        return jdbcTemplate.query(
            PUBLISHED_COLUMNS + " WHERE api.status = 'PUBLISHED' ORDER BY api.id DESC",
            rowMapper
        );
    }

    public Optional<DataApiRecord> findPublishedById(long id) {
        return jdbcTemplate.query(
            PUBLISHED_COLUMNS + " WHERE api.id = ? AND api.status = 'PUBLISHED'",
            rowMapper,
            id
        ).stream().findFirst();
    }

    public List<ApiVersionRecord> findVersions(long apiId) {
        return jdbcTemplate.query("""
            SELECT id, api_id, version_no, dataset_id, name, description, path, method,
                   query_sql, parameters_json, cache_ttl_seconds, max_page_size, status,
                   change_summary, created_by, submitted_by, submitted_at, reviewed_by,
                   reviewed_at, review_comment, published_at, source_version_id, created_at
            FROM data_service_api_version
            WHERE api_id = ?
            ORDER BY version_no DESC
            """, versionRowMapper, apiId);
    }

    public Optional<ApiVersionRecord> findVersion(long apiId, int versionNo) {
        return jdbcTemplate.query("""
            SELECT id, api_id, version_no, dataset_id, name, description, path, method,
                   query_sql, parameters_json, cache_ttl_seconds, max_page_size, status,
                   change_summary, created_by, submitted_by, submitted_at, reviewed_by,
                   reviewed_at, review_comment, published_at, source_version_id, created_at
            FROM data_service_api_version
            WHERE api_id = ? AND version_no = ?
            """, versionRowMapper, apiId, versionNo).stream().findFirst();
    }

    @Transactional
    public DataApiRecord create(
        long datasetId,
        String name,
        String description,
        String path,
        String method,
        String querySql,
        List<ApiParameter> parameters,
        Integer cacheTtlSeconds,
        int maxPageSize,
        String actor,
        String changeSummary
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String parameterJson = writeParameters(parameters);
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_api
                    (dataset_id, name, description, path, method, query_sql, parameters_json,
                     status, version, cache_ttl_seconds, max_page_size)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?)
                """, new String[]{"id"});
            bindDefinition(
                statement, datasetId, name, description, path, method, querySql,
                parameterJson, cacheTtlSeconds, maxPageSize, 1
            );
            return statement;
        }, keyHolder);
        long apiId = keyHolder.getKey().longValue();
        insertVersion(
            apiId, 1, datasetId, name, description, path, method, querySql,
            parameterJson, cacheTtlSeconds, maxPageSize, "DRAFT",
            changeSummary, actor, null, null
        );
        return findById(apiId).orElseThrow();
    }

    @Transactional
    public DataApiRecord update(
        long id,
        long datasetId,
        String name,
        String description,
        String path,
        String method,
        String querySql,
        List<ApiParameter> parameters,
        Integer cacheTtlSeconds,
        int maxPageSize,
        String actor,
        String changeSummary
    ) {
        DataApiRecord current = findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API 不存在"));
        lifecyclePolicy.assertEditable(current.latestVersionStatus());
        int nextVersion = nextVersion(id);
        String parameterJson = writeParameters(parameters);
        int updated = jdbcTemplate.update("""
            UPDATE data_service_api
            SET dataset_id = ?, name = ?, description = ?, path = ?, method = ?,
                query_sql = ?, parameters_json = ?, version = ?,
                cache_ttl_seconds = ?, max_page_size = ?,
                status = CASE WHEN published_version_id IS NULL THEN 'DRAFT' ELSE status END
            WHERE id = ?
            """,
            datasetId, name, description, path, method, querySql, parameterJson, nextVersion,
            cacheTtlSeconds, maxPageSize, id
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API 不存在");
        }
        insertVersion(
            id, nextVersion, datasetId, name, description, path, method, querySql,
            parameterJson, cacheTtlSeconds, maxPageSize, "DRAFT",
            changeSummary, actor, null, null
        );
        return findById(id).orElseThrow();
    }

    public ApiVersionRecord submitForApproval(long apiId, String actor) {
        ApiVersionRecord latest = latestVersion(apiId);
        lifecyclePolicy.assertSubmittable(latest.status());
        jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'PENDING_APPROVAL', submitted_by = ?, submitted_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, actor, latest.id());
        return findVersion(apiId, latest.versionNo()).orElseThrow();
    }

    @Transactional
    public DataApiRecord approve(long apiId, int versionNo, String reviewer, String comment) {
        ApiVersionRecord version = requirePendingVersion(apiId, versionNo);
        lifecyclePolicy.assertReviewable(version.status(), version.submittedBy(), reviewer);
        jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'ARCHIVED'
            WHERE api_id = ? AND status = 'PUBLISHED'
            """, apiId);
        jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'PUBLISHED', reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                review_comment = ?, published_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, reviewer, comment, version.id());
        applyVersionToApi(apiId, version, version.id(), "PUBLISHED");
        return findById(apiId).orElseThrow();
    }

    public ApiVersionRecord markCanary(
        long apiId,
        int versionNo,
        String reviewer,
        String comment
    ) {
        ApiVersionRecord version = requirePendingVersion(apiId, versionNo);
        lifecyclePolicy.assertReviewable(version.status(), version.submittedBy(), reviewer);
        jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'CANARY', reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                review_comment = ?
            WHERE id = ?
            """, reviewer, comment, version.id());
        return findVersion(apiId, versionNo).orElseThrow();
    }

    @Transactional
    public DataApiRecord promoteCanary(
        long apiId,
        int versionNo,
        String reviewer,
        String comment
    ) {
        ApiVersionRecord version = findVersion(apiId, versionNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API version not found"));
        if (!"CANARY".equals(version.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "API version is not in canary");
        }
        jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'ARCHIVED'
            WHERE api_id = ? AND status = 'PUBLISHED'
            """, apiId);
        jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'PUBLISHED', reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                review_comment = ?, published_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, reviewer, comment, version.id());
        ApiVersionRecord published = findVersion(apiId, versionNo).orElseThrow();
        applyVersionToApi(apiId, published, version.id(), "PUBLISHED");
        return findById(apiId).orElseThrow();
    }

    public ApiVersionRecord archiveCanary(long apiId, int versionNo, String actor) {
        int updated = jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'ARCHIVED', review_comment = CONCAT(
                COALESCE(review_comment, ''), CASE WHEN review_comment IS NULL THEN '' ELSE '; ' END,
                'Canary rolled back by ', ?
            )
            WHERE api_id = ? AND version_no = ? AND status = 'CANARY'
            """, actor, apiId, versionNo);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Canary API version is not active");
        }
        return findVersion(apiId, versionNo).orElseThrow();
    }

    public ApiVersionRecord reject(long apiId, int versionNo, String reviewer, String comment) {
        ApiVersionRecord version = requirePendingVersion(apiId, versionNo);
        lifecyclePolicy.assertReviewable(version.status(), version.submittedBy(), reviewer);
        jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'REJECTED', reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP,
                review_comment = ?
            WHERE id = ?
            """, reviewer, comment, version.id());
        return findVersion(apiId, versionNo).orElseThrow();
    }

    @Transactional
    public DataApiRecord rollback(
        long apiId,
        int sourceVersionNo,
        String reviewer,
        String changeSummary
    ) {
        ApiVersionRecord source = findVersion(apiId, sourceVersionNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "历史版本不存在"));
        lifecyclePolicy.assertRollbackSource(source.status());
        int nextVersion = nextVersion(apiId);
        jdbcTemplate.update("""
            UPDATE data_service_api_version
            SET status = 'ARCHIVED'
            WHERE api_id = ? AND status = 'PUBLISHED'
            """, apiId);
        long newVersionId = insertVersion(
            apiId, nextVersion, source.datasetId(), source.name(), source.description(),
            source.path(), source.method(), source.querySql(), writeParameters(source.parameters()),
            source.cacheTtlSeconds(), source.maxPageSize(), "PUBLISHED",
            changeSummary, reviewer, source.id(), reviewer
        );
        ApiVersionRecord rollbackVersion = findVersion(apiId, nextVersion).orElseThrow();
        applyVersionToApi(apiId, rollbackVersion, newVersionId, "PUBLISHED");
        return findById(apiId).orElseThrow();
    }

    public DataApiRecord offline(long apiId) {
        int updated = jdbcTemplate.update("""
            UPDATE data_service_api SET status = 'OFFLINE' WHERE id = ?
            """, apiId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API 不存在");
        }
        return findById(apiId).orElseThrow();
    }

    private void applyVersionToApi(
        long apiId,
        ApiVersionRecord version,
        long publishedVersionId,
        String status
    ) {
        jdbcTemplate.update("""
            UPDATE data_service_api
            SET dataset_id = ?, name = ?, description = ?, path = ?, method = ?,
                query_sql = ?, parameters_json = ?, status = ?, version = ?,
                published_version_id = ?, cache_ttl_seconds = ?, max_page_size = ?,
                published_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            version.datasetId(), version.name(), version.description(), version.path(),
            version.method(), version.querySql(), writeParameters(version.parameters()), status,
            version.versionNo(), publishedVersionId, version.cacheTtlSeconds(),
            version.maxPageSize(), apiId
        );
    }

    private ApiVersionRecord latestVersion(long apiId) {
        return findVersions(apiId).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API 版本不存在"));
    }

    private ApiVersionRecord requirePendingVersion(long apiId, int versionNo) {
        ApiVersionRecord version = findVersion(apiId, versionNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API 版本不存在"));
        return version;
    }

    private int nextVersion(long apiId) {
        Integer maxVersion = jdbcTemplate.queryForObject("""
            SELECT COALESCE(MAX(version_no), 0)
            FROM data_service_api_version
            WHERE api_id = ?
            """, Integer.class, apiId);
        return (maxVersion == null ? 0 : maxVersion) + 1;
    }

    private long insertVersion(
        long apiId,
        int versionNo,
        long datasetId,
        String name,
        String description,
        String path,
        String method,
        String querySql,
        String parametersJson,
        Integer cacheTtlSeconds,
        int maxPageSize,
        String status,
        String changeSummary,
        String createdBy,
        Long sourceVersionId,
        String reviewer
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO data_service_api_version
                  (api_id, version_no, dataset_id, name, description, path, method, query_sql,
                   parameters_json, cache_ttl_seconds, max_page_size, status, change_summary,
                   created_by, reviewed_by, reviewed_at, published_at, source_version_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CASE WHEN ? IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END,
                        CASE WHEN ? = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END, ?)
                """, new String[]{"id"});
            int index = 1;
            statement.setLong(index++, apiId);
            statement.setInt(index++, versionNo);
            statement.setLong(index++, datasetId);
            statement.setString(index++, name);
            statement.setString(index++, description);
            statement.setString(index++, path);
            statement.setString(index++, method);
            statement.setString(index++, querySql);
            statement.setString(index++, parametersJson);
            if (cacheTtlSeconds == null) {
                statement.setNull(index++, java.sql.Types.INTEGER);
            } else {
                statement.setInt(index++, cacheTtlSeconds);
            }
            statement.setInt(index++, maxPageSize);
            statement.setString(index++, status);
            statement.setString(index++, changeSummary);
            statement.setString(index++, createdBy);
            statement.setString(index++, reviewer);
            statement.setString(index++, reviewer);
            statement.setString(index++, status);
            if (sourceVersionId == null) {
                statement.setNull(index, java.sql.Types.BIGINT);
            } else {
                statement.setLong(index, sourceVersionId);
            }
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void bindDefinition(
        java.sql.PreparedStatement statement,
        long datasetId,
        String name,
        String description,
        String path,
        String method,
        String querySql,
        String parametersJson,
        Integer cacheTtlSeconds,
        int maxPageSize,
        int startIndex
    ) throws SQLException {
        int index = startIndex;
        statement.setLong(index++, datasetId);
        statement.setString(index++, name);
        statement.setString(index++, description);
        statement.setString(index++, path);
        statement.setString(index++, method);
        statement.setString(index++, querySql);
        statement.setString(index++, parametersJson);
        if (cacheTtlSeconds == null) {
            statement.setNull(index++, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index++, cacheTtlSeconds);
        }
        statement.setInt(index, maxPageSize);
    }

    private DataApiRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new DataApiRecord(
            rs.getLong("id"),
            rs.getLong("dataset_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("path"),
            rs.getString("method"),
            rs.getString("query_sql"),
            readParameters(rs.getString("parameters_json")),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getString("latest_version_status"),
            (Integer) rs.getObject("published_version"),
            (Integer) rs.getObject("cache_ttl_seconds"),
            rs.getInt("max_page_size"),
            toInstant(rs.getTimestamp("published_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private ApiVersionRecord mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new ApiVersionRecord(
            rs.getLong("id"),
            rs.getLong("api_id"),
            rs.getInt("version_no"),
            rs.getLong("dataset_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("path"),
            rs.getString("method"),
            rs.getString("query_sql"),
            readParameters(rs.getString("parameters_json")),
            (Integer) rs.getObject("cache_ttl_seconds"),
            rs.getInt("max_page_size"),
            rs.getString("status"),
            rs.getString("change_summary"),
            rs.getString("created_by"),
            rs.getString("submitted_by"),
            toInstant(rs.getTimestamp("submitted_at")),
            rs.getString("reviewed_by"),
            toInstant(rs.getTimestamp("reviewed_at")),
            rs.getString("review_comment"),
            toInstant(rs.getTimestamp("published_at")),
            (Long) rs.getObject("source_version_id"),
            toInstant(rs.getTimestamp("created_at"))
        );
    }

    private String writeParameters(List<ApiParameter> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters == null ? List.of() : parameters);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("参数定义无法序列化", exception);
        }
    }

    private List<ApiParameter> readParameters(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的参数定义格式不正确", exception);
        }
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
