package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.ApiParameter;
import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.ContractFinding;
import com.company.dataops.dataservice.domain.ContractReport;
import com.company.dataops.dataservice.repository.ContractGovernanceRepository;
import com.company.dataops.dataservice.repository.DataApiRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContractGovernanceService {
    private final DataApiRepository apiRepository;
    private final ContractGovernanceRepository reportRepository;

    public ContractGovernanceService(
        DataApiRepository apiRepository,
        ContractGovernanceRepository reportRepository
    ) {
        this.apiRepository = apiRepository;
        this.reportRepository = reportRepository;
    }

    public ContractReport analyze(long apiId, int versionNo) {
        ApiVersionRecord candidate = apiRepository.findVersion(apiId, versionNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API version not found"));
        ApiVersionRecord baseline = apiRepository.findPublishedById(apiId)
            .flatMap(api -> apiRepository.findVersion(apiId, api.version()))
            .orElse(null);
        List<ContractFinding> findings = baseline == null
            ? List.of(new ContractFinding(
                "INFO", "INITIAL_VERSION", "API", "Initial publication has no compatibility baseline"
            ))
            : compare(baseline, candidate);
        String severity = severity(findings);
        return reportRepository.save(
            apiId,
            versionNo,
            baseline == null ? null : baseline.versionNo(),
            severity,
            findings
        );
    }

    public ContractReport report(long apiId, int versionNo) {
        return reportRepository.find(apiId, versionNo)
            .orElseGet(() -> analyze(apiId, versionNo));
    }

    private List<ContractFinding> compare(ApiVersionRecord baseline, ApiVersionRecord candidate) {
        List<ContractFinding> findings = new ArrayList<>();
        breakingIfChanged(findings, "PATH_CHANGED", "Path", baseline.path(), candidate.path());
        breakingIfChanged(findings, "METHOD_CHANGED", "Method", baseline.method(), candidate.method());

        Map<String, ApiParameter> oldParameters = parameters(baseline.parameters());
        Map<String, ApiParameter> newParameters = parameters(candidate.parameters());
        oldParameters.forEach((key, oldParameter) -> {
            ApiParameter current = newParameters.get(key);
            if (current == null) {
                findings.add(breaking("PARAMETER_REMOVED", oldParameter.name(), "Parameter was removed"));
                return;
            }
            if (!oldParameter.type().equals(current.type())) {
                findings.add(breaking("PARAMETER_TYPE_CHANGED", oldParameter.name(),
                    oldParameter.type() + " changed to " + current.type()));
            }
            if (!oldParameter.location().equals(current.location())) {
                findings.add(breaking("PARAMETER_LOCATION_CHANGED", oldParameter.name(),
                    oldParameter.location() + " changed to " + current.location()));
            }
            if (!oldParameter.required() && current.required()) {
                findings.add(breaking("PARAMETER_BECAME_REQUIRED", oldParameter.name(),
                    "Optional parameter became required"));
            }
            if (!java.util.Objects.equals(oldParameter.defaultValue(), current.defaultValue())) {
                findings.add(risky("PARAMETER_DEFAULT_CHANGED", oldParameter.name(),
                    "Default value changed"));
            }
        });
        newParameters.forEach((key, parameter) -> {
            if (!oldParameters.containsKey(key)) {
                findings.add(parameter.required()
                    ? breaking("REQUIRED_PARAMETER_ADDED", parameter.name(), "New required parameter")
                    : info("OPTIONAL_PARAMETER_ADDED", parameter.name(), "New optional parameter"));
            }
        });

        Set<String> oldColumns = outputColumns(baseline.querySql());
        Set<String> newColumns = outputColumns(candidate.querySql());
        oldColumns.stream()
            .filter(column -> !newColumns.contains(column))
            .forEach(column -> findings.add(
                breaking("RESPONSE_FIELD_REMOVED", column, "Response field was removed")
            ));
        newColumns.stream()
            .filter(column -> !oldColumns.contains(column))
            .forEach(column -> findings.add(
                info("RESPONSE_FIELD_ADDED", column, "Response field was added")
            ));

        if (!baseline.datasetId().equals(candidate.datasetId())) {
            findings.add(risky("DATASET_CHANGED", "Dataset", "Backing dataset changed"));
        }
        if (candidate.maxPageSize() < baseline.maxPageSize()) {
            findings.add(risky("MAX_PAGE_SIZE_REDUCED", "Pagination",
                baseline.maxPageSize() + " reduced to " + candidate.maxPageSize()));
        }
        if (findings.isEmpty()) {
            findings.add(info("NO_CONTRACT_CHANGE", "API", "No externally visible contract change"));
        }
        return findings;
    }

    private Map<String, ApiParameter> parameters(List<ApiParameter> parameters) {
        Map<String, ApiParameter> result = new LinkedHashMap<>();
        parameters.forEach(parameter ->
            result.put(parameter.name().toLowerCase(Locale.ROOT), parameter)
        );
        return result;
    }

    private Set<String> outputColumns(String sql) {
        try {
            Select select = (Select) CCJSqlParserUtil.parse(sql);
            PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
            Set<String> columns = new LinkedHashSet<>();
            plainSelect.getSelectItems().forEach(item -> {
                String name = item.getAlias() == null
                    ? item.getExpression().toString()
                    : item.getAlias().getName();
                int dot = name.lastIndexOf('.');
                columns.add((dot >= 0 ? name.substring(dot + 1) : name)
                    .replace("\"", "")
                    .replace("`", "")
                    .toLowerCase(Locale.ROOT));
            });
            return columns;
        } catch (Exception exception) {
            return Set.of();
        }
    }

    private void breakingIfChanged(
        List<ContractFinding> findings,
        String code,
        String subject,
        Object baseline,
        Object candidate
    ) {
        if (!java.util.Objects.equals(baseline, candidate)) {
            findings.add(breaking(code, subject, baseline + " changed to " + candidate));
        }
    }

    private String severity(List<ContractFinding> findings) {
        if (findings.stream().anyMatch(item -> "BREAKING".equals(item.level()))) {
            return "BREAKING";
        }
        if (findings.stream().anyMatch(item -> "RISKY".equals(item.level()))) {
            return "RISKY";
        }
        return "COMPATIBLE";
    }

    private ContractFinding breaking(String code, String subject, String message) {
        return new ContractFinding("BREAKING", code, subject, message);
    }

    private ContractFinding risky(String code, String subject, String message) {
        return new ContractFinding("RISKY", code, subject, message);
    }

    private ContractFinding info(String code, String subject, String message) {
        return new ContractFinding("INFO", code, subject, message);
    }
}
