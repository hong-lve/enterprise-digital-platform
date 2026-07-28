package com.company.dataops.dataservice.security;

import org.springframework.http.HttpStatus;

public class GatewaySecurityException extends RuntimeException {
    private final HttpStatus status;

    public GatewaySecurityException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
