package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.mapper.CdcSourceMapper;
import com.company.dataops.console.service.kafka.AvroSchemaDiffService;
import com.company.dataops.console.service.kafka.SchemaRegistryClient;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Schema evolution / data contract surface (tier 2 item 1 of the
 * reliability roadmap). Subject names (e.g. "mysqldemo.cdc_demo.biz_item-
 * value") are passed as query params rather than path segments throughout -
 * dots in path variables have historically been a source of subtle routing
 * bugs in Spring MVC (trailing-suffix stripping), and a query param sidesteps
 * that class of issue entirely rather than relying on the current
 * PathPatternParser default being dot-safe.
 */
@RestController
@RequestMapping("/realtime/schema")
public class SchemaRegistryController {
    private final CdcSourceMapper cdcSourceMapper;
    private final SchemaRegistryClient schemaRegistryClient;
    private final AvroSchemaDiffService avroSchemaDiffService;

    public SchemaRegistryController(CdcSourceMapper cdcSourceMapper, SchemaRegistryClient schemaRegistryClient, AvroSchemaDiffService avroSchemaDiffService) {
        this.cdcSourceMapper = cdcSourceMapper;
        this.schemaRegistryClient = schemaRegistryClient;
        this.avroSchemaDiffService = avroSchemaDiffService;
    }

    @GetMapping("/cdc-sources/{cdcSourceId}/subjects")
    @PreAuthorize("hasAuthority('realtime:cdc:view')")
    public ApiResponse<List<SubjectSummary>> subjects(@PathVariable Long cdcSourceId) {
        CdcSourceEntity source = cdcSourceMapper.selectById(cdcSourceId);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CDC 数据源不存在");
        }
        return ApiResponse.ok(Arrays.stream(source.getTableIncludeList().split(","))
            .map(String::trim)
            .map(tableRef -> {
                String topic = source.getTopicPrefix() + "." + tableRef;
                String subject = topic + "-value";
                List<Integer> versions = schemaRegistryClient.versions(subject);
                if (versions.isEmpty()) {
                    return new SubjectSummary(tableRef, topic, subject, versions, null, null);
                }
                int latest = versions.stream().mapToInt(Integer::intValue).max().orElseThrow();
                return new SubjectSummary(tableRef, topic, subject, versions, latest, schemaRegistryClient.compatibilityLevel(subject));
            })
            .toList());
    }

    @GetMapping("/diff")
    @PreAuthorize("hasAuthority('realtime:cdc:view')")
    public ApiResponse<DiffResponse> diff(@RequestParam String subject, @RequestParam int fromVersion, @RequestParam int toVersion) {
        String oldSchema = schemaRegistryClient.schema(subject, fromVersion);
        String newSchema = schemaRegistryClient.schema(subject, toVersion);
        if (oldSchema == null || newSchema == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指定的 schema 版本不存在");
        }
        SchemaRegistryClient.CompatibilityResult compatibility = schemaRegistryClient.checkCompatibility(subject, fromVersion, newSchema);
        AvroSchemaDiffService.FieldDiff fieldDiff = avroSchemaDiffService.diff(oldSchema, newSchema);
        return ApiResponse.ok(new DiffResponse(
            fromVersion, toVersion, compatibility.compatible(), compatibility.detail(),
            fieldDiff.removedFields(), fieldDiff.addedFields(), fieldDiff.typeChanges()));
    }

    @PutMapping("/compatibility")
    @PreAuthorize("hasAuthority('realtime:cdc:schema-manage')")
    public ApiResponse<Void> setCompatibility(@RequestParam String subject, @RequestBody CompatibilityRequest request) {
        schemaRegistryClient.setCompatibilityLevel(subject, request.level());
        return ApiResponse.ok();
    }

    public record SubjectSummary(String tableRef, String topic, String subject, List<Integer> versions, Integer latestVersion, String compatibilityLevel) {
    }

    public record DiffResponse(
        int fromVersion,
        int toVersion,
        boolean compatible,
        String compatibilityDetail,
        List<String> removedFields,
        List<String> addedFields,
        List<AvroSchemaDiffService.FieldTypeChange> typeChanges
    ) {
    }

    public record CompatibilityRequest(String level) {
    }
}
