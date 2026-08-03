package com.company.dataops.console.service.kafka;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thin wrapper over Confluent Schema Registry's REST API (deployed by the
 * {@code schema-registry} container in docker/bigdata/docker-compose.yml -
 * plain HTTP on its client-facing listener, only its own Kafka-side
 * connection was migrated to SSL, so no TLS/auth to configure here). Nothing
 * in this codebase called the registry's REST API directly before this -
 * CdcLagInspector and CdcTableSchemaService only ever used the Avro
 * *deserializer* library or interpolated the URL into generated SQL.
 *
 * Plain default RestTemplate (unlike KafkaConnectClient's
 * JdkClientHttpRequestFactory) - confirmed live that the JDK HTTP client
 * factory actively breaks PUT /config/{subject} against this server
 * ("Received RST_STREAM: Stream cancelled", an HTTP/2-level reset Java's
 * HttpClient triggers against Schema Registry's Jetty that the default
 * HttpURLConnection-based factory doesn't hit), even though the same
 * instance's GET calls worked fine either way. KafkaConnectClient's own
 * factory swap fixed a *different* server's *different* quirk (a 415 on
 * DELETE) - not a general rule to apply to every Kafka-adjacent REST client.
 */
@Component
public class SchemaRegistryClient {
    private static final MediaType SCHEMA_REGISTRY_JSON = MediaType.parseMediaType("application/vnd.schemaregistry.v1+json");

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    public SchemaRegistryClient(@Value("${platform.bigdata.schema-registry-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** GET /subjects/{subject}/versions - empty if the subject doesn't exist yet (nothing has ever been written to that topic). */
    public List<Integer> versions(String subject) {
        try {
            Integer[] result = restTemplate.getForObject(baseUrl + "/subjects/" + encode(subject) + "/versions", Integer[].class);
            return result == null ? List.of() : List.of(result);
        } catch (HttpClientErrorException.NotFound notFound) {
            return List.of();
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Schema Registry 查询版本列表：" + exception.getMessage());
        }
    }

    /** GET /subjects/{subject}/versions/{version} - the raw Avro schema JSON string for that version, or null if it doesn't exist. */
    public String schema(String subject, int version) {
        try {
            Map<?, ?> result = restTemplate.getForObject(baseUrl + "/subjects/" + encode(subject) + "/versions/" + version, Map.class);
            return result == null ? null : String.valueOf(result.get("schema"));
        } catch (HttpClientErrorException.NotFound notFound) {
            return null;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Schema Registry 获取 schema：" + exception.getMessage());
        }
    }

    /**
     * GET /config/{subject}, falling back to the registry's global GET
     * /config if the subject has no override - a subject without one isn't
     * an error, it just means "use whatever the cluster default is".
     */
    public String compatibilityLevel(String subject) {
        try {
            Map<?, ?> result = restTemplate.getForObject(baseUrl + "/config/" + encode(subject), Map.class);
            if (result != null && result.get("compatibilityLevel") != null) {
                return String.valueOf(result.get("compatibilityLevel"));
            }
        } catch (HttpClientErrorException.NotFound notFound) {
            // no subject-level override - fall through to the global default
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Schema Registry 查询兼容模式：" + exception.getMessage());
        }
        try {
            Map<?, ?> globalResult = restTemplate.getForObject(baseUrl + "/config", Map.class);
            return globalResult == null || globalResult.get("compatibilityLevel") == null
                ? "BACKWARD" : String.valueOf(globalResult.get("compatibilityLevel"));
        } catch (Exception exception) {
            return "BACKWARD"; // Schema Registry's own documented default - best-effort display only, never blocks anything
        }
    }

    /** PUT /config/{subject} - sets a subject-level compatibility override. */
    public void setCompatibilityLevel(String subject, String level) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(SCHEMA_REGISTRY_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("compatibility", level), headers);
        try {
            restTemplate.exchange(baseUrl + "/config/" + encode(subject), HttpMethod.PUT, request, Void.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "设置 Schema Registry 兼容模式失败：" + exception.getMessage());
        }
    }

    /**
     * POST /compatibility/subjects/{subject}/versions/{version} - runs
     * Schema Registry's own compatibility check (its actual enforcement
     * logic, not a reimplementation of Avro's compatibility rules) between
     * candidateSchema and the schema already registered as that version.
     */
    public CompatibilityResult checkCompatibility(String subject, int againstVersion, String candidateSchemaJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(SCHEMA_REGISTRY_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("schema", candidateSchemaJson), headers);
        Map<?, ?> result;
        try {
            result = restTemplate.postForObject(
                baseUrl + "/compatibility/subjects/" + encode(subject) + "/versions/" + againstVersion + "?verbose=true", request, Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Schema Registry 检查兼容性：" + exception.getMessage());
        }
        if (result == null) {
            return new CompatibilityResult(false, "Schema Registry 无响应");
        }
        boolean compatible = Boolean.TRUE.equals(result.get("is_compatible"));
        Object messages = result.get("messages");
        String detail = messages instanceof List<?> list && !list.isEmpty() ? String.join("; ", list.stream().map(String::valueOf).toList()) : null;
        return new CompatibilityResult(compatible, detail);
    }

    private static String encode(String subject) {
        return subject.replace("/", "%2F");
    }

    public record CompatibilityResult(boolean compatible, String detail) {
    }
}
