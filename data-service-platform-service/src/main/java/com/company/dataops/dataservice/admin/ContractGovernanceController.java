package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.AdminUserRecord;
import com.company.dataops.dataservice.domain.ContractAssertion;
import com.company.dataops.dataservice.domain.ContractReport;
import com.company.dataops.dataservice.domain.ContractTestCase;
import com.company.dataops.dataservice.domain.ContractTestRun;
import com.company.dataops.dataservice.repository.DataApiRepository;
import com.company.dataops.dataservice.service.ContractGovernanceService;
import com.company.dataops.dataservice.service.ContractTestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data-service-admin/contract-governance/apis/{apiId}")
public class ContractGovernanceController {
    private final ContractGovernanceService governanceService;
    private final ContractTestService testService;
    private final DataApiRepository apiRepository;

    public ContractGovernanceController(
        ContractGovernanceService governanceService,
        ContractTestService testService,
        DataApiRepository apiRepository
    ) {
        this.governanceService = governanceService;
        this.testService = testService;
        this.apiRepository = apiRepository;
    }

    @GetMapping("/versions/{versionNo}/report")
    public ApiResponse<ContractReport> report(
        @PathVariable long apiId,
        @PathVariable int versionNo
    ) {
        return ApiResponse.ok(governanceService.report(apiId, versionNo));
    }

    @PostMapping("/versions/{versionNo}/analyze")
    public ApiResponse<ContractReport> analyze(
        @PathVariable long apiId,
        @PathVariable int versionNo
    ) {
        return ApiResponse.ok(governanceService.analyze(apiId, versionNo));
    }

    @GetMapping("/cases")
    public ApiResponse<List<ContractTestCase>> cases(@PathVariable long apiId) {
        return ApiResponse.ok(testService.cases(apiId));
    }

    @PostMapping("/cases")
    public ApiResponse<ContractTestCase> createCase(
        @PathVariable long apiId,
        @Valid @RequestBody SaveCaseRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(saveCase(null, apiId, request, authentication));
    }

    @PutMapping("/cases/{caseId}")
    public ApiResponse<ContractTestCase> updateCase(
        @PathVariable long apiId,
        @PathVariable long caseId,
        @Valid @RequestBody SaveCaseRequest request,
        Authentication authentication
    ) {
        return ApiResponse.ok(saveCase(caseId, apiId, request, authentication));
    }

    @PostMapping("/cases/{caseId}/run")
    public ApiResponse<ContractTestRun> runCase(
        @PathVariable long apiId,
        @PathVariable long caseId,
        @RequestParam(required = false) Integer versionNo,
        Authentication authentication
    ) {
        int targetVersion = versionNo == null
            ? apiRepository.findById(apiId).orElseThrow().version()
            : versionNo;
        return ApiResponse.ok(testService.runCase(
            apiId, caseId, targetVersion, actor(authentication)
        ));
    }

    @GetMapping("/runs")
    public ApiResponse<List<ContractTestRun>> runs(@PathVariable long apiId) {
        return ApiResponse.ok(testService.runs(apiId));
    }

    private ContractTestCase saveCase(
        Long caseId,
        long apiId,
        SaveCaseRequest request,
        Authentication authentication
    ) {
        return testService.saveCase(
            caseId,
            apiId,
            request.name(),
            request.enabled(),
            request.parameters(),
            request.page(),
            request.pageSize(),
            request.assertions(),
            actor(authentication)
        );
    }

    private static String actor(Authentication authentication) {
        return ((AdminUserRecord) authentication.getPrincipal()).username();
    }

    public record SaveCaseRequest(
        @NotBlank String name,
        boolean enabled,
        Map<String, Object> parameters,
        @Min(1) int page,
        @Min(1) @Max(500) int pageSize,
        @NotNull List<ContractAssertion> assertions
    ) {
    }
}
