package com.company.dataops.dataservice.domain;

public record CreatedApplication(
    ApplicationRecord application,
    String appSecret
) {
}
