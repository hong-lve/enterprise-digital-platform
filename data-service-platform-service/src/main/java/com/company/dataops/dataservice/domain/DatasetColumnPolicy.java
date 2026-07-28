package com.company.dataops.dataservice.domain;

public record DatasetColumnPolicy(
    String columnName,
    String action,
    String maskType
) {
}
