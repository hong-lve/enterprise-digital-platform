package com.company.dataops.console.service.flink;

import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.service.monitoring.RealtimeMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Submits a FlinkSqlJobEntity's script (N CREATE TABLE + one INSERT INTO)
 * as a persistent Flink job via the SQL Gateway, distinct from
 * FlinkSqlQueryService's interactive/read-only use of the same gateway.
 * Confirmed live that closing the session after submission does NOT cancel
 * the job the INSERT INTO already handed to the cluster - unlike
 * FlinkSqlQueryService, which has to keep its session open for the whole
 * query because an interactive SELECT's result-collection job is tied to
 * the session, this can always close in finally.
 *
 * Once submitted, the resulting flinkJobId is just another Flink job id -
 * FlinkStreamSubmissionClient.status()/stopWithSavepoint() (job-id based,
 * agnostic to how the job was originally submitted) handle status/stop for
 * both jar-submitted and SQL-submitted jobs; this class only owns
 * submission.
 */
@Component
public class FlinkSqlJobSubmissionService {
    private static final long PER_STATEMENT_BUDGET_MILLIS = 8_000;

    private final FlinkSqlGatewayClient client;
    private final FlinkCapacityInspector capacityInspector;
    private final FlinkSqlSecretResolver secretResolver;
    private final RealtimeMetrics metrics;
    private final FlinkClusterRegistryService clusterRegistryService;

    public FlinkSqlJobSubmissionService(FlinkSqlGatewayClient client, FlinkCapacityInspector capacityInspector,
                                        FlinkSqlSecretResolver secretResolver, RealtimeMetrics metrics,
                                        FlinkClusterRegistryService clusterRegistryService) {
        this.client = client;
        this.capacityInspector = capacityInspector;
        this.secretResolver = secretResolver;
        this.metrics = metrics;
        this.clusterRegistryService = clusterRegistryService;
    }

    public String submit(FlinkSqlJobEntity job) {
        String materializedSql = secretResolver.resolveForSubmission(job.getSqlScript());
        List<String> statements = FlinkSqlGatewayClient.splitStatements(materializedSql);
        assertJobShape(statements);
        var cluster = clusterRegistryService.resolve(job.getClusterId(), job.getEnvironment());
        if (cluster.getRestUrl() != null && !cluster.getRestUrl().isBlank()) {
            capacityInspector.requireCapacity(cluster.getRestUrl(), job.getParallelism() == null ? 1 : job.getParallelism(), job.getEnvironment());
        }
        String sessionHandle = client.openSession(buildProperties(job), cluster.getId(), job.getEnvironment());
        try {
            String jobId = null;
            for (String statement : statements) {
                String operationHandle = client.executeStatement(sessionHandle, statement);
                long deadline = System.currentTimeMillis() + PER_STATEMENT_BUDGET_MILLIS;
                FlinkSqlGatewayClient.QueryResult result = client.awaitResult(sessionHandle, operationHandle, deadline);
                if (result.jobId() != null) {
                    jobId = result.jobId();
                }
            }
            if (jobId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "提交失败：没有拿到 Flink job id");
            }
            metrics.jobSubmission("sql", true);
            return clusterRegistryService.encodeJobId(cluster, jobId);
        } catch (RuntimeException exception) {
            metrics.jobSubmission("sql", false);
            throw exception;
        } finally {
            client.closeSession(sessionHandle);
        }
    }

    /**
     * Requires zero-or-more CREATE TABLE statements followed by exactly one
     * INSERT INTO as the last statement - v1 doesn't support multi-sink
     * STATEMENT SET, so anything else (a bare SELECT, DDL after the INSERT,
     * two INSERT INTOs, DROP/ALTER/DELETE/UPDATE) is rejected up front
     * rather than failing confusingly mid-submission.
     */
    public void assertJobShape(List<String> statements) {
        if (statements.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL 不能为空");
        }
        for (int i = 0; i < statements.size(); i++) {
            String normalized = statements.get(i).trim().toUpperCase(Locale.ROOT);
            boolean isLast = i == statements.size() - 1;
            if (isLast) {
                if (!normalized.startsWith("INSERT")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL 作业最后一条语句必须是 INSERT INTO");
                }
            } else if (!normalized.startsWith("CREATE TABLE")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SQL 作业只支持若干 CREATE TABLE 后跟一条 INSERT INTO，第 " + (i + 1) + " 条语句不符合要求");
            }
        }
    }

    // parallelism.default/execution.checkpointing.interval confirmed live to
    // take effect on the submitted job via session properties. restart
    // strategy keys do not (see V10__flink_sql_job.sql) so aren't set here.
    // execution.savepoint.path follows the same properties mechanism as the
    // confirmed-working keys above but wasn't independently verified live -
    // confirm this specifically when testing stop-then-restart end to end.
    private Map<String, String> buildProperties(FlinkSqlJobEntity job) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("parallelism.default", String.valueOf(job.getParallelism() == null ? 1 : job.getParallelism()));
        int checkpointIntervalMs = job.getCheckpointIntervalMs() == null ? 60000 : job.getCheckpointIntervalMs();
        properties.put("execution.checkpointing.interval", checkpointIntervalMs + " ms");
        if (job.getSavepointPath() != null && !job.getSavepointPath().isBlank()) {
            properties.put("execution.savepoint.path", job.getSavepointPath());
        }
        return properties;
    }
}
