package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.DataApiRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiReleaseGateService {
    private final ApiExecutionService executionService;
    private final ContractGovernanceService contractGovernanceService;
    private final ContractTestService contractTestService;

    public ApiReleaseGateService(
        ApiExecutionService executionService,
        ContractGovernanceService contractGovernanceService,
        ContractTestService contractTestService
    ) {
        this.executionService = executionService;
        this.contractGovernanceService = contractGovernanceService;
        this.contractTestService = contractTestService;
    }

    public void verify(DataApiRecord api, ApiVersionRecord version, String actor) {
        executionService.validateDefinition(version.asApi(api));
        var report = contractGovernanceService.analyze(api.id(), version.versionNo());
        if (report.breaking()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Contract validation failed: breaking changes cannot be released"
            );
        }
        var suite = contractTestService.runRequiredSuite(api.id(), version.versionNo(), actor);
        if (!suite.passed()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Contract test suite failed: " + suite.failureMessage()
            );
        }
    }
}
