package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.repository.GovernanceRepository;
import org.springframework.stereotype.Service;

@Service
public class OperationAuditService {
    private final GovernanceRepository repository;

    public OperationAuditService(GovernanceRepository repository) {
        this.repository = repository;
    }

    public void record(
        String actor,
        String clientIp,
        String traceId,
        String httpMethod,
        String requestPath,
        String operation,
        String resourceId,
        int statusCode,
        String errorMessage
    ) {
        repository.saveAudit(
            actor,
            clientIp,
            traceId,
            httpMethod,
            requestPath,
            operation,
            resourceId,
            statusCode < 400 ? "SUCCESS" : "FAILURE",
            statusCode,
            errorMessage
        );
    }
}
