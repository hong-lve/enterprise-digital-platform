package com.company.dataops.console.service.query;

/** Shared shape between ClickHouseQueryService and DorisQueryService's column listings. */
public record ColumnView(String name, String type, Integer size, String remarks) {
}
