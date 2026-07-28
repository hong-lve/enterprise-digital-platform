package com.company.dataops.dataservice.domain;

public record ApiParameter(
    String name,
    String location,
    String type,
    boolean required,
    String defaultValue,
    String description
) {
}
