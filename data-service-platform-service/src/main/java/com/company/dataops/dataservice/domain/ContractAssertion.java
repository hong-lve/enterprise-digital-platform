package com.company.dataops.dataservice.domain;

public record ContractAssertion(
    String type,
    String field,
    String expected
) {
}
