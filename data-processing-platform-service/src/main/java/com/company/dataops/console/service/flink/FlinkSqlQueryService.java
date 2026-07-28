package com.company.dataops.console.service.flink;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates FlinkSqlGatewayClient's session/statement/result protocol
 * into a single blocking call: open a session, run every statement in the
 * script in order (so a CREATE TABLE mapping a Kafka topic is visible to the
 * SELECT that follows it), return the last statement's result, always close
 * the session. Scope is deliberately read-only/interactive - see
 * docker/bigdata/README.md for why writing to ClickHouse via Flink SQL isn't
 * supported by any viable connector today, so long-running sink jobs still
 * go through hand-written jars like flink-jobs/task-stats-job instead.
 */
@Service
public class FlinkSqlQueryService {
    /**
     * Total wall-clock budget for the whole script, not per statement -
     * keeps this a bounded preview call. Confirmed live that a fresh Kafka
     * source table typically returns its first rows within ~1-2s of the
     * SELECT being submitted, so this leaves ample margin without making a
     * "preview" click feel sluggish. Kept comfortably below the frontend's
     * per-call timeout (api/flinkSql.ts, 15s) - this budget is spent
     * polling, on top of which network/JSON overhead still applies, so it
     * must not be right at the client timeout's edge (confirmed live: an
     * earlier 10s/10s split raced and the client aborted first).
     */
    private static final long TIME_BUDGET_MILLIS = 8_000;

    private final FlinkSqlGatewayClient client;

    public FlinkSqlQueryService(FlinkSqlGatewayClient client) {
        this.client = client;
    }

    public FlinkSqlGatewayClient.QueryResult execute(String script) {
        List<String> statements = FlinkSqlGatewayClient.splitStatements(script);
        if (statements.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL 不能为空");
        }
        statements.forEach(this::assertAllowed);

        String sessionHandle = client.openSession();
        try {
            long deadline = System.currentTimeMillis() + TIME_BUDGET_MILLIS;
            FlinkSqlGatewayClient.QueryResult result = new FlinkSqlGatewayClient.QueryResult(List.of(), List.of(), null);
            for (String statement : statements) {
                String operationHandle = client.executeStatement(sessionHandle, statement);
                result = client.awaitResult(sessionHandle, operationHandle, deadline);
            }
            return result;
        } finally {
            // Always close, success or failure - an open session keeps its
            // last operation's Flink job running (confirmed live: FINISHED
            // status alone doesn't free the task slot), and this environment
            // only has 2 total.
            client.closeSession(sessionHandle);
        }
    }

    private static final Pattern CONNECTOR_OPTION = Pattern.compile(
        "'connector'\\s*=\\s*'([^']+)'", Pattern.CASE_INSENSITIVE
    );

    /**
     * Unlike ClickHouseQueryService.assertSelect() (SELECT-only), CREATE
     * TABLE is allowed here - it only registers a Kafka topic mapping in
     * this session's local catalog, not a persisted change to any real
     * system, so it carries none of the risk a DDL against a live database
     * would. What's blocked is anything that mutates external state or
     * starts a long-running sink job (INSERT INTO is exactly the
     * "write to ClickHouse via SQL" path this feature deliberately excludes).
     *
     * That "no real system" reasoning only holds for the kafka/upsert-kafka
     * connector this feature is documented for (FlinkSqlPage's example
     * script) - Flink table DDL accepts a 'connector' option naming any
     * connector on the classpath, and a filesystem/jdbc table pointed at an
     * arbitrary path or host turns a supposedly read-only preview query into
     * a way to read local files or probe internal hosts from the Flink
     * cluster. So CREATE TABLE is allowed only when its WITH clause names a
     * kafka connector; anything else is rejected the same as INSERT/DROP/etc.
     */
    private void assertAllowed(String statement) {
        String normalized = statement.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("INSERT") || normalized.startsWith("DROP") || normalized.startsWith("ALTER")
            || normalized.startsWith("DELETE") || normalized.startsWith("UPDATE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不允许 INSERT/DROP/ALTER/DELETE/UPDATE 语句，这里只做交互式查询");
        }
        if (normalized.startsWith("CREATE")) {
            Matcher matcher = CONNECTOR_OPTION.matcher(statement);
            // Exact match, not contains(): a substring check would also pass
            // a custom connector merely named e.g. "my-kafka-mirror" that
            // isn't actually the kafka connector. Nothing on this cluster's
            // classpath is named that today, but matching the literal
            // connector identifier Flink itself resolves by is the correct
            // check regardless of what happens to be deployed.
            String connector = matcher.find() ? matcher.group(1).trim().toLowerCase(Locale.ROOT) : "";
            if (!connector.equals("kafka") && !connector.equals("upsert-kafka")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CREATE TABLE 只允许映射 kafka/upsert-kafka connector");
            }
        }
    }
}
