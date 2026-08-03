package com.company.dataops.console.service.lineage;

import com.company.dataops.console.service.flink.FlinkSqlGatewayClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Component;

/**
 * Tier 2 item 3 of the reliability roadmap ("自动血缘和列级血缘") - upgrades
 * lineage from the hand-filled kafkaTopics/*SinkTables fields on
 * FlinkSqlJobEntity (see LineageController) to parsing the job's own
 * sqlScript. Two different parsing strategies for the two different
 * statement kinds a SQL job's script contains:
 *
 * - CREATE TABLE ... WITH (...) is hand-parsed (paren-balanced substring
 *   extraction, not JSqlParser) because Flink's WITH-clause connector
 *   properties are a Flink-specific extension, not ANSI SQL or any dialect
 *   JSqlParser understands - confirmed by reading real generated examples
 *   (CdcTableSchemaService/SinkTableDdlBuilder), which are consistently
 *   simple enough (backtick-quoted column list, 'key'='value' WITH options,
 *   optional PRIMARY KEY (...) NOT ENFORCED) that hand-parsing is both
 *   reliable and far simpler than fighting a parser that doesn't know this
 *   syntax.
 * - INSERT INTO sink SELECT ... FROM source [JOIN ...] genuinely is ANSI
 *   SQL, so this uses JSqlParser (already a proven dependency in this repo -
 *   see the sibling data-service-platform-service module) to resolve exactly
 *   which source table.column each target column derives from. This
 *   platform's own generators (CdcTableSchemaService, SinkTableDdlBuilder)
 *   only ever emit bare backtick-quoted column references with no explicit
 *   INSERT column list, positional against the SELECT list - the common
 *   case this resolves precisely. A hand-written/edited job with a computed
 *   expression (CAST, arithmetic, a function call) degrades to a warning
 *   rather than a wrong answer, since precise multi-column derivation isn't
 *   needed for anything this platform generates itself.
 */
@Component
public class FlinkSqlLineageParser {

    public SqlLineageResult parse(String sqlScript) {
        List<String> statements = FlinkSqlGatewayClient.splitStatements(sqlScript);
        Map<String, TableLineage> tables = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String insertStatement = null;

        for (String raw : statements) {
            String statement = raw.trim();
            if (statement.regionMatches(true, 0, "CREATE TABLE", 0, "CREATE TABLE".length())) {
                try {
                    TableLineage table = parseCreateTable(statement);
                    tables.put(table.tableName(), table);
                } catch (Exception exception) {
                    warnings.add("无法解析建表语句（" + firstLine(statement) + "）：" + exception.getMessage());
                }
            } else if (statement.regionMatches(true, 0, "INSERT INTO", 0, "INSERT INTO".length())) {
                insertStatement = statement;
            }
        }

        if (insertStatement == null) {
            warnings.add("未找到 INSERT INTO 语句，无法生成字段级血缘");
            return new SqlLineageResult(new ArrayList<>(tables.values()), null, List.of(), warnings);
        }
        try {
            return parseInsertSelect(insertStatement, tables, warnings);
        } catch (Exception exception) {
            warnings.add("无法解析 INSERT 语句的字段级血缘：" + exception.getMessage());
            return new SqlLineageResult(new ArrayList<>(tables.values()), null, List.of(), warnings);
        }
    }

    private SqlLineageResult parseInsertSelect(String sql, Map<String, TableLineage> tables, List<String> warnings) throws Exception {
        Statement parsed = CCJSqlParserUtil.parse(sql);
        if (!(parsed instanceof Insert insert)) {
            throw new IllegalArgumentException("不是合法的 INSERT 语句");
        }
        String targetTable = insert.getTable().getName();
        if (!(insert.getSelect().getSelectBody() instanceof PlainSelect plainSelect)) {
            throw new IllegalArgumentException("暂不支持 UNION/复杂子查询的血缘解析");
        }

        Map<String, String> aliasToTable = new LinkedHashMap<>();
        registerFromItem(plainSelect.getFromItem(), aliasToTable);
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                registerFromItem(join.getRightItem(), aliasToTable);
            }
        }
        String soleSourceTable = aliasToTable.size() == 1 ? aliasToTable.values().iterator().next() : null;

        List<String> explicitTargetColumns = insert.getColumns() == null ? null
            : insert.getColumns().stream().map(Column::getColumnName).map(this::unquote).toList();
        TableLineage targetTableLineage = tables.get(targetTable);

        List<SelectItem<?>> items = plainSelect.getSelectItems();
        List<ColumnLineage> lineages = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SelectItem<?> item = items.get(i);
            List<SourceColumnRef> sourceRefs = new ArrayList<>();
            if (item.getExpression() instanceof Column column) {
                String tableRef = column.getTable() != null ? column.getTable().getName() : null;
                String resolved = tableRef != null ? aliasToTable.getOrDefault(tableRef, tableRef) : soleSourceTable;
                sourceRefs.add(new SourceColumnRef(resolved, unquote(column.getColumnName())));
            } else {
                // Not a bare column reference - this platform's own generated
                // SQL never produces these, only a hand-edited job would.
                warnings.add("第 " + (i + 1) + " 个查询列不是简单字段引用（" + item.getExpression() + "），未记录精确血缘");
            }
            String targetColumn = item.getAlias() != null ? item.getAlias().getName()
                : explicitTargetColumns != null && i < explicitTargetColumns.size() ? explicitTargetColumns.get(i)
                : targetTableLineage != null && i < targetTableLineage.columns().size() ? targetTableLineage.columns().get(i)
                : sourceRefs.size() == 1 ? sourceRefs.get(0).column()
                : "col" + (i + 1);
            lineages.add(new ColumnLineage(unquote(targetColumn), sourceRefs, item.getExpression().toString()));
        }
        return new SqlLineageResult(new ArrayList<>(tables.values()), targetTable, lineages, warnings);
    }

    private void registerFromItem(FromItem fromItem, Map<String, String> aliasToTable) {
        if (fromItem instanceof Table table) {
            String tableName = table.getName();
            String alias = table.getAlias() != null ? table.getAlias().getName() : tableName;
            aliasToTable.put(alias, tableName);
            aliasToTable.put(tableName, tableName);
        }
    }

    private TableLineage parseCreateTable(String sql) {
        Matcher nameMatcher = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (!nameMatcher.find()) {
            throw new IllegalArgumentException("解析不出表名");
        }
        String tableName = nameMatcher.group(1);
        int columnsStart = nameMatcher.end() - 1;
        int columnsEnd = findMatchingParen(sql, columnsStart);
        List<String> columns = extractColumnNames(sql.substring(columnsStart + 1, columnsEnd));

        Map<String, String> properties = new LinkedHashMap<>();
        Matcher withMatcher = Pattern.compile("WITH\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (withMatcher.find(columnsEnd)) {
            int withStart = withMatcher.end() - 1;
            int withEnd = findMatchingParen(sql, withStart);
            Matcher propMatcher = Pattern.compile("'([^']+)'\\s*=\\s*'([^']*)'").matcher(sql.substring(withStart + 1, withEnd));
            while (propMatcher.find()) {
                properties.put(propMatcher.group(1), propMatcher.group(2));
            }
        }

        String connector = properties.getOrDefault("connector", "unknown");
        String physicalLocation = switch (connector) {
            case "kafka", "upsert-kafka" -> properties.getOrDefault("topic", "-");
            case "jdbc" -> properties.getOrDefault("table-name", "-");
            case "doris" -> properties.getOrDefault("table.identifier", "-");
            case "redis" -> "key-prefix=" + properties.getOrDefault("key-prefix", "-");
            default -> properties.isEmpty() ? "-" : properties.toString();
        };
        return new TableLineage(tableName, connector, physicalLocation, columns);
    }

    private List<String> extractColumnNames(String columnsBlock) {
        List<String> columns = new ArrayList<>();
        for (String part : splitTopLevelCommas(columnsBlock)) {
            String trimmed = part.trim();
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (trimmed.isEmpty() || upper.startsWith("PRIMARY KEY") || upper.startsWith("WATERMARK") || upper.startsWith("CONSTRAINT")) {
                continue; // not an actual column definition
            }
            Matcher columnMatcher = Pattern.compile("^`?(\\w+)`?\\s").matcher(trimmed + " ");
            if (columnMatcher.find()) {
                columns.add(columnMatcher.group(1));
            }
        }
        return columns;
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

    private String unquote(String identifier) {
        return identifier == null ? null : identifier.replace("`", "").replace("\"", "");
    }

    private String firstLine(String sql) {
        int newline = sql.indexOf('\n');
        String line = newline > 0 ? sql.substring(0, newline) : sql;
        return line.length() > 60 ? line.substring(0, 60) + "..." : line;
    }

    public record SourceColumnRef(String table, String column) {
    }

    public record ColumnLineage(String targetColumn, List<SourceColumnRef> sourceColumns, String expression) {
    }

    public record TableLineage(String tableName, String connectorType, String physicalLocation, List<String> columns) {
    }

    public record SqlLineageResult(List<TableLineage> tables, String targetTable, List<ColumnLineage> columnLineages, List<String> warnings) {
    }
}
