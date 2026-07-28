package com.company.dataops.console.service.query;

import java.util.List;
import java.util.Map;

/** Shared shape between ClickHouseQueryService and DorisQueryService's query results. */
public record QueryResult(List<String> columns, List<Map<String, Object>> rows) {
}
