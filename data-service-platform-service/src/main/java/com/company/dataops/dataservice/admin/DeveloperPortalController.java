package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.ApiParameter;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.repository.DataApiRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/data-service-admin/developer-portal")
public class DeveloperPortalController {
    private final DataApiRepository repository;

    public DeveloperPortalController(DataApiRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/apis")
    public ApiResponse<List<DataApiRecord>> catalog() {
        return ApiResponse.ok(repository.findAll().stream()
            .filter(api -> "PUBLISHED".equals(api.status()))
            .toList());
    }

    @GetMapping("/apis/{id}/openapi")
    public ApiResponse<Map<String, Object>> openApiDocument(@PathVariable long id) {
        DataApiRecord api = repository.findById(id)
            .filter(item -> "PUBLISHED".equals(item.status()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Published API not found"));
        return ApiResponse.ok(buildDocument(api));
    }

    private Map<String, Object> buildDocument(DataApiRecord api) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("summary", api.name());
        operation.put("description", api.description() == null ? "" : api.description());
        operation.put("operationId", "dataServiceApi" + api.id());

        List<Map<String, Object>> parameters = new ArrayList<>();
        Map<String, Object> bodyProperties = new LinkedHashMap<>();
        List<String> requiredBody = new ArrayList<>();
        for (ApiParameter parameter : api.parameters()) {
            Map<String, Object> schema = schema(parameter.type());
            if ("BODY".equals(parameter.location())) {
                bodyProperties.put(parameter.name(), schema);
                if (parameter.required()) {
                    requiredBody.add(parameter.name());
                }
            } else {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", parameter.name());
                item.put("in", "HEADER".equals(parameter.location()) ? "header" : "query");
                item.put("required", parameter.required());
                item.put("description", parameter.description() == null ? "" : parameter.description());
                item.put("schema", schema);
                parameters.add(item);
            }
        }
        operation.put("parameters", parameters);
        if (!bodyProperties.isEmpty()) {
            Map<String, Object> bodySchema = new LinkedHashMap<>();
            bodySchema.put("type", "object");
            bodySchema.put("properties", bodyProperties);
            if (!requiredBody.isEmpty()) {
                bodySchema.put("required", requiredBody);
            }
            operation.put("requestBody", Map.of(
                "required", !requiredBody.isEmpty(),
                "content", Map.of("application/json", Map.of("schema", bodySchema))
            ));
        }
        operation.put("responses", Map.of(
            "200", Map.of("description", "Successful data-service response"),
            "401", Map.of("description", "Invalid application signature"),
            "403", Map.of("description", "Subscription is unavailable"),
            "429", Map.of("description", "Subscription quota exceeded")
        ));

        return Map.of(
            "openapi", "3.0.3",
            "info", Map.of(
                "title", api.name(),
                "version", String.valueOf(api.publishedVersion()),
                "description", api.description() == null ? "" : api.description()
            ),
            "servers", List.of(Map.of("url", "/openapi")),
            "paths", Map.of(api.path(), Map.of(api.method().toLowerCase(), operation))
        );
    }

    private Map<String, Object> schema(String type) {
        return switch (type) {
            case "INTEGER" -> Map.of("type", "integer", "format", "int32");
            case "LONG" -> Map.of("type", "integer", "format", "int64");
            case "DECIMAL" -> Map.of("type", "number", "format", "double");
            case "BOOLEAN" -> Map.of("type", "boolean");
            case "DATE" -> Map.of("type", "string", "format", "date");
            case "DATETIME" -> Map.of("type", "string", "format", "date-time");
            default -> Map.of("type", "string");
        };
    }
}
