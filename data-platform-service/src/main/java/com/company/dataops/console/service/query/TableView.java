package com.company.dataops.console.service.query;

/** Shared shape between ClickHouseQueryService and DorisQueryService's table listings. */
public record TableView(String name, String type, String remarks) {
}
