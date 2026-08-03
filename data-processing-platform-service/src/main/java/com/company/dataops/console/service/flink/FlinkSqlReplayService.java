package com.company.dataops.console.service.flink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Tier 3 item 1 of the reliability roadmap ("历史数据回放、按时间/Offset 重放和
 * Backfill"): lets a SQL job be (re)submitted reading its Kafka source(s)
 * from a specific point in time, or from the very beginning, instead of
 * wherever its consumer group last left off - for reprocessing a historical
 * window (the exact time range an incident or a since-fixed bug covered)
 * without touching the job's own stored sqlScript. buildReplayScript()
 * builds a one-time modified script for a single submission only; the
 * job's persisted definition is never rewritten.
 *
 * Only rewrites plain 'connector'='kafka' source tables (this platform's own
 * convention for a CDC-mirrored Kafka source - see CdcTableSchemaService),
 * never 'upsert-kafka' (always a sink in this platform's generated SQL).
 * Doesn't apply to jar-based jobs (FlinkStreamJobEntity) at all - their
 * Kafka source configuration is compiled into the jar itself, not something
 * this platform can rewrite from outside.
 */
@Component
public class FlinkSqlReplayService {
    private static final Pattern WITH_KEYWORD = Pattern.compile("\\bWITH\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern KAFKA_CONNECTOR = Pattern.compile("'connector'\\s*=\\s*'kafka'", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPERTY = Pattern.compile("'([^']+)'\\s*=\\s*'([^']*)'");

    public String buildReplayScript(String originalSqlScript, ReplayMode mode, Long timestampMillis) {
        if (mode == ReplayMode.NORMAL) {
            return originalSqlScript;
        }
        if (mode == ReplayMode.FROM_TIMESTAMP && timestampMillis == null) {
            throw new IllegalArgumentException("按时间点重放必须提供时间戳");
        }
        List<String> statements = FlinkSqlGatewayClient.splitStatements(originalSqlScript);
        List<String> rebuilt = new ArrayList<>();
        boolean rewroteAny = false;
        for (String raw : statements) {
            String statement = raw.trim();
            boolean isKafkaSource = statement.regionMatches(true, 0, "CREATE TABLE", 0, "CREATE TABLE".length())
                && KAFKA_CONNECTOR.matcher(statement).find();
            if (isKafkaSource) {
                rebuilt.add(overrideStartupMode(statement, mode, timestampMillis));
                rewroteAny = true;
            } else {
                rebuilt.add(statement);
            }
        }
        if (!rewroteAny) {
            throw new IllegalArgumentException("这个作业的 SQL 里没有找到 'connector'='kafka' 的源表，无法重放");
        }
        return String.join(";\n\n", rebuilt) + ";";
    }

    private String overrideStartupMode(String createTableSql, ReplayMode mode, Long timestampMillis) {
        Matcher withMatcher = WITH_KEYWORD.matcher(createTableSql);
        if (!withMatcher.find()) {
            throw new IllegalArgumentException("解析不出 WITH 子句：" + firstLine(createTableSql));
        }
        int withStart = withMatcher.end() - 1;
        int withEnd = findMatchingParen(createTableSql, withStart);

        Map<String, String> properties = new LinkedHashMap<>();
        for (String part : splitTopLevelCommas(createTableSql.substring(withStart + 1, withEnd))) {
            Matcher propMatcher = PROPERTY.matcher(part);
            if (propMatcher.find()) {
                properties.put(propMatcher.group(1), propMatcher.group(2));
            }
        }
        properties.remove("scan.startup.timestamp-millis");
        properties.remove("scan.startup.specific-offsets");
        if (mode == ReplayMode.FROM_EARLIEST) {
            properties.put("scan.startup.mode", "earliest-offset");
        } else {
            properties.put("scan.startup.mode", "timestamp");
            properties.put("scan.startup.timestamp-millis", String.valueOf(timestampMillis));
        }

        StringBuilder newWithBody = new StringBuilder("\n");
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            newWithBody.append("  '").append(entry.getKey()).append("' = '").append(entry.getValue()).append("',\n");
        }
        newWithBody.setLength(newWithBody.length() - 2); // drop the trailing ",\n" left after the last entry
        newWithBody.append('\n');

        return createTableSql.substring(0, withStart + 1) + newWithBody + createTableSql.substring(withEnd);
    }

    private int findMatchingParen(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("括号未闭合");
    }

    private List<String> splitTopLevelCommas(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private String firstLine(String sql) {
        int newline = sql.indexOf('\n');
        return newline > 0 ? sql.substring(0, newline) : sql;
    }

    public enum ReplayMode {
        NORMAL, FROM_EARLIEST, FROM_TIMESTAMP
    }
}
