package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.ContractAssertion;
import com.company.dataops.dataservice.domain.ContractTestCase;
import com.company.dataops.dataservice.domain.ContractTestRun;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.ExecutionResult;
import com.company.dataops.dataservice.repository.ContractTestRepository;
import com.company.dataops.dataservice.repository.DataApiRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContractTestService {
    private static final Set<String> ASSERTION_TYPES = Set.of(
        "ROW_COUNT_MIN", "ROW_COUNT_MAX", "MAX_ELAPSED_MS",
        "FIELD_EXISTS", "FIELD_NOT_NULL", "FIELD_TYPE", "FIELD_EQUALS"
    );

    private final ContractTestRepository repository;
    private final DataApiRepository apiRepository;
    private final ApiExecutionService executionService;

    public ContractTestService(
        ContractTestRepository repository,
        DataApiRepository apiRepository,
        ApiExecutionService executionService
    ) {
        this.repository = repository;
        this.apiRepository = apiRepository;
        this.executionService = executionService;
    }

    public List<ContractTestCase> cases(long apiId) {
        requireApi(apiId);
        return repository.findCases(apiId);
    }

    public List<ContractTestRun> runs(long apiId) {
        requireApi(apiId);
        return repository.findRuns(apiId, 100);
    }

    public ContractTestCase saveCase(
        Long id,
        long apiId,
        String name,
        boolean enabled,
        Map<String, Object> parameters,
        int page,
        int pageSize,
        List<ContractAssertion> assertions,
        String actor
    ) {
        DataApiRecord api = requireApi(apiId);
        if (name == null || name.isBlank()) {
            throw badRequest("Test case name is required");
        }
        if (page < 1 || pageSize < 1 || pageSize > api.maxPageSize()) {
            throw badRequest("Invalid contract test pagination");
        }
        if (assertions == null || assertions.isEmpty()) {
            throw badRequest("At least one assertion is required");
        }
        assertions.forEach(this::validateAssertion);
        return repository.save(
            id, apiId, name.trim(), enabled,
            parameters == null ? Map.of() : parameters,
            page, pageSize, assertions, actor
        );
    }

    public ContractTestRun runCase(long apiId, long caseId, int versionNo, String actor) {
        DataApiRecord api = requireApi(apiId);
        ApiVersionRecord version = apiRepository.findVersion(apiId, versionNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API version not found"));
        ContractTestCase testCase = repository.findCase(caseId)
            .filter(item -> item.apiId() == apiId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract test case not found"));
        try {
            ExecutionResult result = executionService.execute(
                version.asApi(api),
                testCase.parameters(),
                testCase.page(),
                testCase.pageSize(),
                "contract-test",
                "127.0.0.1",
                true
            );
            String failure = evaluate(testCase.assertions(), result);
            return repository.saveRun(
                caseId, apiId, versionNo,
                failure == null ? "PASSED" : "FAILED",
                result.elapsedMs(), result.rowCount(), failure, actor
            );
        } catch (RuntimeException exception) {
            return repository.saveRun(
                caseId, apiId, versionNo, "ERROR",
                null, null, rootMessage(exception), actor
            );
        }
    }

    public SuiteResult runRequiredSuite(long apiId, int versionNo, String actor) {
        List<ContractTestCase> cases = repository.findEnabledCases(apiId);
        if (cases.isEmpty()) {
            return new SuiteResult(false, List.of(), "At least one enabled contract test is required");
        }
        List<ContractTestRun> runs = cases.stream()
            .map(testCase -> runCase(apiId, testCase.id(), versionNo, actor))
            .toList();
        boolean passed = runs.stream().allMatch(run -> "PASSED".equals(run.status()));
        return new SuiteResult(
            passed,
            runs,
            passed ? null : runs.stream()
                .filter(run -> !"PASSED".equals(run.status()))
                .map(run -> run.failureMessage() == null ? run.status() : run.failureMessage())
                .findFirst()
                .orElse("Contract test failed")
        );
    }

    private String evaluate(List<ContractAssertion> assertions, ExecutionResult result) {
        for (ContractAssertion assertion : assertions) {
            String failure = evaluate(assertion, result);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private String evaluate(ContractAssertion assertion, ExecutionResult result) {
        return switch (assertion.type().toUpperCase(Locale.ROOT)) {
            case "ROW_COUNT_MIN" -> result.rowCount() >= integer(assertion.expected())
                ? null : "Expected at least " + assertion.expected() + " rows";
            case "ROW_COUNT_MAX" -> result.rowCount() <= integer(assertion.expected())
                ? null : "Expected at most " + assertion.expected() + " rows";
            case "MAX_ELAPSED_MS" -> result.elapsedMs() <= longValue(assertion.expected())
                ? null : "Elapsed time exceeded " + assertion.expected() + " ms";
            case "FIELD_EXISTS" -> !result.rows().isEmpty()
                && result.rows().stream().allMatch(row -> resolve(row, assertion.field()).found())
                ? null : "Field is missing: " + assertion.field();
            case "FIELD_NOT_NULL" -> !result.rows().isEmpty() && result.rows().stream()
                .map(row -> resolve(row, assertion.field()))
                .allMatch(value -> value.found() && value.value() != null)
                ? null : "Field is null or missing: " + assertion.field();
            case "FIELD_TYPE" -> !result.rows().isEmpty() && result.rows().stream()
                .map(row -> resolve(row, assertion.field()))
                .allMatch(value -> value.found()
                    && (value.value() == null || matchesType(value.value(), assertion.expected())))
                ? null : "Field type mismatch: " + assertion.field();
            case "FIELD_EQUALS" -> result.rows().isEmpty()
                || !resolve(result.rows().get(0), assertion.field()).found()
                || !String.valueOf(resolve(result.rows().get(0), assertion.field()).value()).equals(assertion.expected())
                ? "Field value mismatch: " + assertion.field()
                : null;
            default -> "Unsupported assertion: " + assertion.type();
        };
    }

    @SuppressWarnings("unchecked")
    private Value resolve(Map<String, Object> row, String path) {
        if (path == null || path.isBlank()) {
            return new Value(false, null);
        }
        Object current = row;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return new Value(false, null);
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return new Value(true, current);
    }

    private boolean matchesType(Object value, String expected) {
        return switch (expected.toUpperCase(Locale.ROOT)) {
            case "STRING" -> value instanceof String;
            case "NUMBER" -> value instanceof Number || value instanceof BigDecimal;
            case "INTEGER" -> value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
            case "BOOLEAN" -> value instanceof Boolean;
            case "OBJECT" -> value instanceof Map;
            case "ARRAY" -> value instanceof List;
            default -> false;
        };
    }

    private void validateAssertion(ContractAssertion assertion) {
        String type = assertion.type() == null ? "" : assertion.type().toUpperCase(Locale.ROOT);
        if (!ASSERTION_TYPES.contains(type)) {
            throw badRequest("Unsupported assertion type: " + assertion.type());
        }
        if (type.startsWith("FIELD_") && (assertion.field() == null || assertion.field().isBlank())) {
            throw badRequest("Field assertion requires a field path");
        }
        if (!Set.of("FIELD_EXISTS", "FIELD_NOT_NULL").contains(type)
            && (assertion.expected() == null || assertion.expected().isBlank())) {
            throw badRequest("Assertion requires an expected value");
        }
        if (Set.of("ROW_COUNT_MIN", "ROW_COUNT_MAX").contains(type)) {
            integer(assertion.expected());
        }
        if ("MAX_ELAPSED_MS".equals(type)) {
            longValue(assertion.expected());
        }
        if ("FIELD_TYPE".equals(type)
            && !Set.of("STRING", "NUMBER", "INTEGER", "BOOLEAN", "OBJECT", "ARRAY")
                .contains(assertion.expected().toUpperCase(Locale.ROOT))) {
            throw badRequest("Unsupported expected field type: " + assertion.expected());
        }
    }

    private DataApiRecord requireApi(long apiId) {
        return apiRepository.findById(apiId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API not found"));
    }

    private int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw badRequest("Assertion value must be an integer");
        }
    }

    private long longValue(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw badRequest("Assertion value must be an integer");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private record Value(boolean found, Object value) {
    }

    public record SuiteResult(boolean passed, List<ContractTestRun> runs, String failureMessage) {
    }
}
