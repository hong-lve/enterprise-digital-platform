package com.company.dataops.console.service.datasource;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared bare-identifier validation for admin-supplied table/column names
 * spliced directly into generated SQL (DataReconciliationService,
 * DataQualityRuleService) - kept in one place deliberately since it's a SQL
 * injection guard, not incidental duplication: two independent copies of a
 * security check drifting out of sync is a real risk, unlike two copies of
 * ordinary logic.
 */
public final class SqlIdentifierValidator {
    private SqlIdentifierValidator() {
    }

    /** Table name, optionally schema-qualified (Oracle's "SCHEMA.TABLE"). */
    public static String requireValidTableName(String table) {
        if (table == null || !table.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的表名：" + table);
        }
        return table;
    }

    public static String requireValidColumnName(String column, String fieldLabel) {
        if (column == null || !column.matches("[A-Za-z0-9_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法的" + fieldLabel + "：" + column);
        }
        return column;
    }
}
