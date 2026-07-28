package com.company.dataops.dataservice.domain;

public record ApplicationCredential(
    Long id,
    String appKey,
    String encryptedSecret,
    String status,
    Integer qpsLimit,
    Integer secretVersion
) {
}
