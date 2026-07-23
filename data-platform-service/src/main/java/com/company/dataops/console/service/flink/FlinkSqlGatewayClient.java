package com.company.dataops.console.service.flink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Talks to Flink's SQL Gateway (docker/bigdata's flink-sql-gateway container,
 * REST port 18084), which runs in "remote" execution mode against the same
 * flink-jobmanager session cluster FlinkStreamSubmissionClient submits jars
 * to. Unlike that client's fire-and-forget jar submission, SQL Gateway is a
 * stateful session protocol: open a session, submit a statement (returns an
 * operation handle), poll the operation, fetch its result, close the session
 * when done. Session/operation lifecycle is deliberately not hidden behind a
 * single call the way FlinkStreamSubmissionClient.submit() is - the caller
 * (FlinkSqlQueryService) needs to run multiple statements in the same
 * session (CREATE TABLE then SELECT) before closing it.
 */
@Component
public class FlinkSqlGatewayClient {
    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    public FlinkSqlGatewayClient(@Value("${platform.bigdata.flink-sql-gateway-url:http://localhost:18084}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String openSession() {
        return openSession(Map.of());
    }

    /**
     * properties becomes the session's initial Flink Configuration - used by
     * FlinkSqlJobSubmissionService to set parallelism/checkpointing for a
     * persistent job. Confirmed live: execution.checkpointing.interval and
     * parallelism.default here do take effect on the resulting job; restart
     * strategy keys do not (see V10__flink_sql_job.sql for why
     * FlinkSqlJobEntity has no restart-strategy fields).
     */
    public String openSession(Map<String, String> properties) {
        Map<?, ?> result;
        try {
            result = restTemplate.postForObject(baseUrl + "/v1/sessions", Map.of("properties", properties), Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Flink SQL Gateway：" + exception.getMessage());
        }
        if (result == null || result.get("sessionHandle") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "打开 SQL 会话失败");
        }
        return String.valueOf(result.get("sessionHandle"));
    }

    public void closeSession(String sessionHandle) {
        try {
            restTemplate.delete(baseUrl + "/v1/sessions/" + sessionHandle);
        } catch (Exception ignored) {
            // best-effort: the gateway also expires idle sessions on its own,
            // and a failed close here shouldn't turn into an error response
            // for a query that otherwise already succeeded or already failed.
            // Confirmed live this DELETE call works fine against Flink's own
            // Netty-based REST server (unlike KafkaConnectClient.delete()'s
            // equivalent call, which hit Kafka Connect's stricter Jetty/
            // Jersey media-type validation) - the swallow here is purely for
            // the "gateway already expired it" case this comment describes.
        }
    }

    public String executeStatement(String sessionHandle, String statement) {
        Map<?, ?> result;
        try {
            result = restTemplate.postForObject(baseUrl + "/v1/sessions/" + sessionHandle + "/statements", Map.of("statement", statement), Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "提交 SQL 失败：" + exception.getMessage());
        }
        if (result == null || result.get("operationHandle") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "提交 SQL 失败：无响应");
        }
        return String.valueOf(result.get("operationHandle"));
    }

    /**
     * Fetches all result pages by following "nextResultUri" until the
     * gateway reports EOS (or {@code deadlineMillis} passes) - operation
     * status FINISHED does NOT mean the rows are already available in one
     * shot (confirmed live: a LIMIT-bounded streaming SELECT reported
     * FINISHED before its underlying "collect" job had produced any rows
     * yet). A response can also come back "NOT_READY" while Flink is still
     * assembling data; nextResultUri keeps pointing at the same fetch until
     * it's ready, so blindly following it (with a short sleep on NOT_READY)
     * is the documented way to drive this to completion. The deadline keeps
     * this a bounded "preview" call instead of blocking indefinitely; the
     * caller is responsible for closing the session afterward regardless of
     * outcome.
     */
    @SuppressWarnings("unchecked")
    public QueryResult awaitResult(String sessionHandle, String operationHandle, long deadlineMillis) {
        List<String> columns = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        String jobId = null;
        String path = "/v1/sessions/" + sessionHandle + "/operations/" + operationHandle + "/result/0";

        while (System.currentTimeMillis() < deadlineMillis) {
            Map<?, ?> response;
            try {
                response = restTemplate.getForObject(baseUrl + path, Map.class);
            } catch (Exception exception) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "获取查询结果失败：" + exception.getMessage());
            }
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "获取查询结果失败：无响应");
            }
            if (response.get("errors") != null) {
                List<?> errors = (List<?>) response.get("errors");
                String message = errors.isEmpty() ? "未知错误" : String.valueOf(errors.get(0));
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "执行失败：" + message);
            }
            // For a modify statement (INSERT INTO) this is the actual Flink
            // job id - confirmed live it's present as a top-level field on
            // the fetch-result response, distinct from and more reliable
            // than parsing the "job id" column out of the result rows below.
            if (response.get("jobID") != null) {
                jobId = String.valueOf(response.get("jobID"));
            }

            String resultType = String.valueOf(response.get("resultType"));
            if ("PAYLOAD".equals(resultType)) {
                Map<?, ?> results = (Map<?, ?>) response.get("results");
                if (results != null) {
                    if (columns == null) {
                        List<?> columnDefs = (List<?>) results.get("columns");
                        columns = columnDefs == null ? List.of() : columnDefs.stream()
                            .map(column -> String.valueOf(((Map<?, ?>) column).get("name")))
                            .toList();
                    }
                    List<?> data = (List<?>) results.get("data");
                    if (data != null) {
                        List<String> finalColumns = columns;
                        data.forEach(row -> rows.add(toRowMap(finalColumns, (List<Object>) ((Map<?, ?>) row).get("fields"))));
                    }
                }
            }

            Object nextUri = response.get("nextResultUri");
            if (nextUri == null) {
                break; // EOS
            }
            path = String.valueOf(nextUri);
            if ("NOT_READY".equals(resultType)) {
                sleepBriefly();
            }
        }
        return new QueryResult(columns == null ? List.of() : columns, rows, jobId);
    }

    /**
     * Naive split on ';' - fine for the expected usage (a couple of CREATE
     * TABLE statements followed by one SELECT/INSERT INTO), but would
     * misbehave on a statement containing a literal ';' inside a string
     * value (e.g. a WITH option). Not worth a real SQL-aware splitter for a
     * preview/job-submission tool at this scale. Shared between
     * FlinkSqlQueryService (interactive queries) and
     * FlinkSqlJobSubmissionService (persistent SQL jobs) - same protocol
     * detail, not separately-evolving logic.
     */
    public static List<String> splitStatements(String script) {
        return Stream.of(script.split(";"))
            .map(String::trim)
            .filter(statement -> !statement.isEmpty())
            .toList();
    }

    // Gateway rows are positional ("fields": [999, "SUCCESS", ...]); keyed
    // by column name here to match ClickHouseQueryService.QueryResult's
    // shape, which the frontend result table already renders generically.
    private Map<String, Object> toRowMap(List<String> columns, List<Object> fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size() && fields != null && i < fields.size(); i++) {
            row.put(columns.get(i), fields.get(i));
        }
        return row;
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows, String jobId) {
    }
}
