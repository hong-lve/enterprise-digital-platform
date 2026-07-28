package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.ApiParameter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SqlSecurityPolicy {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$.\"]+");
    private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern FUNCTION_CALL = Pattern.compile(
        "(?i)([A-Za-z][A-Za-z0-9_$.]*)\\s*\\("
    );
    private static final Pattern RESTRICTED_CLAUSE = Pattern.compile(
        "(?i)\\b(limit|offset|fetch|into)\\b|\\bfor\\s+update\\b|\\block\\s+in\\s+share\\s+mode\\b"
    );
    private static final Pattern SELECT_KEYWORD = Pattern.compile("(?i)\\bselect\\b");
    private static final Set<String> BLOCKED_FUNCTIONS = Set.of(
        "SLEEP",
        "BENCHMARK",
        "LOAD_FILE",
        "PG_SLEEP",
        "DBMS_LOCK.SLEEP",
        "UTL_HTTP.REQUEST",
        "UTL_HTTP.BEGIN_REQUEST"
    );
    private static final Set<String> TRUSTED_CONTEXT_PARAMETERS = Set.of("_appKey", "_clientIp");

    private final int maxTableReferences;
    private final int maxJoins;
    private final boolean forbidSelectStar;

    public SqlSecurityPolicy(
        @Value("${platform.data-service.sql-security.max-table-references:1}") int maxTableReferences,
        @Value("${platform.data-service.sql-security.max-joins:0}") int maxJoins,
        @Value("${platform.data-service.sql-security.forbid-select-star:true}") boolean forbidSelectStar
    ) {
        this.maxTableReferences = Math.max(1, maxTableReferences);
        this.maxJoins = Math.max(0, maxJoins);
        this.forbidSelectStar = forbidSelectStar;
    }

    public void validate(String sql, String datasetTable, List<ApiParameter> parameters) {
        if (sql == null || sql.isBlank()) {
            reject("查询 SQL 不能为空");
        }
        if (datasetTable == null || !SAFE_IDENTIFIER.matcher(datasetTable).matches()) {
            reject("数据集表名不符合安全规范");
        }

        Select select = parseSingleSelect(sql.trim());
        validateShape(select);
        validateTables(select, datasetTable);
        validateRestrictedClauses(sql);
        validateFunctions(sql);
        validateParameters(sql, parameters == null ? List.of() : parameters);
    }

    public String secureAndValidate(
        String sql,
        String datasetTable,
        List<ApiParameter> parameters,
        String rowFilterSql
    ) {
        String securedSql = applyRowFilter(sql, rowFilterSql);
        validate(securedSql, datasetTable, parameters);
        return securedSql;
    }

    public void validateRowFilter(String rowFilterSql) {
        if (rowFilterSql == null || rowFilterSql.isBlank()) {
            return;
        }
        String expression = rowFilterSql.trim();
        String masked = maskStringLiterals(expression);
        if (masked.matches("(?is).*?(;|--|/\\*|\\*/|\\bselect\\b).*")) {
            reject("行过滤策略只能填写单个条件表达式，不能包含子查询或注释");
        }
        validateRestrictedClauses(expression);
        validateFunctions(expression);
        Matcher parameters = NAMED_PARAMETER.matcher(masked);
        while (parameters.find()) {
            if (!TRUSTED_CONTEXT_PARAMETERS.contains(parameters.group(1))) {
                reject("行过滤策略只允许使用 :_appKey 和 :_clientIp 上下文变量");
            }
        }
        try {
            CCJSqlParserUtil.parseCondExpression(expression);
        } catch (JSQLParserException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "行过滤策略语法解析失败：" + conciseMessage(exception)
            );
        }
    }

    private String applyRowFilter(String sql, String rowFilterSql) {
        if (rowFilterSql == null || rowFilterSql.isBlank()) {
            return sql.trim();
        }
        validateRowFilter(rowFilterSql);
        Select select = parseSingleSelect(sql.trim());
        if (!(select.getSelectBody() instanceof PlainSelect)) {
            reject("行过滤策略只能应用于普通 SELECT 查询");
        }
        PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
        try {
            Expression policyExpression = CCJSqlParserUtil.parseCondExpression(rowFilterSql.trim());
            Expression existing = plainSelect.getWhere();
            plainSelect.setWhere(existing == null
                ? policyExpression
                : new AndExpression(new Parenthesis(existing), new Parenthesis(policyExpression)));
            return select.toString();
        } catch (JSQLParserException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "行过滤策略语法解析失败：" + conciseMessage(exception)
            );
        }
    }

    private Select parseSingleSelect(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.getStatements().size() != 1) {
                reject("只允许执行一条查询语句");
            }
            Statement statement = statements.getStatements().get(0);
            if (!(statement instanceof Select)) {
                reject("数据服务只允许 SELECT 查询");
            }
            return (Select) statement;
        } catch (JSQLParserException exception) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "SQL 语法解析失败：" + conciseMessage(exception)
            );
        }
    }

    private void validateShape(Select select) {
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            reject("当前数据服务不允许 WITH 公共表表达式");
        }
        Matcher selectMatcher = SELECT_KEYWORD.matcher(maskStringLiterals(select.toString()));
        int queryBlocks = 0;
        while (selectMatcher.find()) {
            queryBlocks++;
        }
        if (queryBlocks != 1) {
            reject("当前数据服务不允许嵌套查询");
        }
        if (!(select.getSelectBody() instanceof PlainSelect)) {
            reject("不允许 UNION、INTERSECT、EXCEPT 等集合查询");
        }
        PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
        int joinCount = plainSelect.getJoins() == null ? 0 : plainSelect.getJoins().size();
        if (joinCount > maxJoins) {
            reject("SQL 关联表数量超过平台限制，当前最多允许 " + maxJoins + " 个 JOIN");
        }
        if (forbidSelectStar && plainSelect.getSelectItems().stream()
            .anyMatch(item -> {
                String text = item.toString().trim();
                return "*".equals(text) || text.endsWith(".*");
            })) {
            reject("禁止使用 SELECT *，请明确声明对外返回字段");
        }
    }

    private void validateTables(Select select, String datasetTable) {
        List<String> tables = new TablesNamesFinder().getTableList((Statement) select);
        if (tables.isEmpty()) {
            reject("SQL 必须读取数据集登记的表");
        }
        if (tables.size() > maxTableReferences) {
            reject("SQL 引用表的次数超过平台限制，当前最多允许 " + maxTableReferences + " 次");
        }

        String expected = normalizeIdentifier(datasetTable);
        for (String table : tables) {
            if (!expected.equals(normalizeIdentifier(table))) {
                reject("SQL 只能读取当前数据集登记的表：" + datasetTable);
            }
        }
    }

    private void validateFunctions(String sql) {
        Matcher matcher = FUNCTION_CALL.matcher(maskStringLiterals(sql));
        while (matcher.find()) {
            String function = matcher.group(1).toUpperCase(Locale.ROOT);
            if (BLOCKED_FUNCTIONS.contains(function)) {
                reject("SQL 使用了平台禁止的函数：" + function);
            }
        }
    }

    private void validateRestrictedClauses(String sql) {
        if (RESTRICTED_CLAUSE.matcher(maskStringLiterals(sql)).find()) {
            reject("SQL 包含平台禁止的分页、导出或行锁语法");
        }
    }

    private void validateParameters(String sql, List<ApiParameter> parameters) {
        Set<String> defined = new HashSet<>();
        for (ApiParameter parameter : parameters) {
            if (parameter.name() == null || !parameter.name().matches("[A-Za-z][A-Za-z0-9_]*")) {
                reject("参数名只能包含字母、数字和下划线，且必须以字母开头");
            }
            if (!defined.add(parameter.name())) {
                reject("参数名重复：" + parameter.name());
            }
        }

        Matcher matcher = NAMED_PARAMETER.matcher(maskStringLiterals(sql));
        Set<String> referenced = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            referenced.add(name);
            if (!defined.contains(name) && !TRUSTED_CONTEXT_PARAMETERS.contains(name)) {
                reject("SQL 参数未定义：" + name);
            }
        }
        for (String name : defined) {
            if (!referenced.contains(name)) {
                reject("已定义参数未在 SQL 中使用：" + name);
            }
        }
    }

    private String maskStringLiterals(String sql) {
        StringBuilder masked = new StringBuilder(sql.length());
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '\'') {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    masked.append("  ");
                    index++;
                    continue;
                }
                quoted = !quoted;
                masked.append(' ');
                continue;
            }
            masked.append(quoted ? ' ' : current);
        }
        return masked.toString();
    }

    private String normalizeIdentifier(String identifier) {
        return identifier.replace("`", "")
            .replace("\"", "")
            .toLowerCase(Locale.ROOT);
    }

    private String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "无法识别该 SQL";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private void reject(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
