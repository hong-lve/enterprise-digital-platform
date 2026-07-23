package com.company.dataops.console.service.kafka;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Wraps Kafka Connect's REST API (deployed by the {@code kafka-connect}
 * container in docker/bigdata/docker-compose.yml, which ships the Debezium
 * connector plugins preinstalled). Mirrors the same "plain RestTemplate, no
 * extra HTTP client dependency" style used by data-platform-service's
 * SparkSubmissionClient/FlinkRestClient.
 */
@Component
public class KafkaConnectClient {
    // Confirmed live while building orphaned-connector cleanup: the default
    // SimpleClientHttpRequestFactory's DELETE request against Kafka
    // Connect's Jetty came back "415 Unsupported Media Type", even though
    // an equivalent plain curl DELETE (no special headers) succeeded fine -
    // same class of HttpURLConnection request-construction quirk as the
    // PATCH issue in FlinkClusterReconciliationService. This most likely
    // explains why cdc-source-3/4/5 ended up as real orphans in the first
    // place: CdcSourceController.delete()'s call to delete() below has
    // been silently failing (swallowed by its own best-effort try/catch)
    // every time a CDC source with a deployed connector was removed.
    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());
    private final String baseUrl;

    public KafkaConnectClient(@Value("${platform.bigdata.kafka-connect-url:http://localhost:18083}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * PUT /connectors/{name}/config creates the connector if it doesn't
     * exist yet, or updates+restarts it if it does - a single idempotent
     * call fits a "save and deploy" UI action better than the two-step
     * POST /connectors (create) + separate update endpoint.
     */
    public void deploy(String connectorName, Map<String, Object> config) {
        try {
            restTemplate.put(baseUrl + "/connectors/" + connectorName + "/config", config);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "部署 Kafka Connect connector 失败：" + exception.getMessage());
        }
    }

    public ConnectorStatus status(String connectorName) {
        Map<?, ?> result;
        try {
            result = restTemplate.getForObject(baseUrl + "/connectors/" + connectorName + "/status", Map.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            return new ConnectorStatus("UNKNOWN", "connector 不存在，可能还没启动过或已被删除");
        } catch (Exception exception) {
            return new ConnectorStatus("UNKNOWN", "查询状态失败：" + exception.getMessage());
        }
        if (result == null) {
            return new ConnectorStatus("UNKNOWN", "无响应");
        }
        Map<?, ?> connector = (Map<?, ?>) result.get("connector");
        String connectorState = connector == null ? "UNKNOWN" : String.valueOf(connector.get("state"));
        if ("FAILED".equals(connectorState)) {
            return new ConnectorStatus("FAILED", String.valueOf(connector.get("trace")));
        }

        List<?> tasks = (List<?>) result.get("tasks");
        if (tasks != null) {
            for (Object taskObj : tasks) {
                Map<?, ?> task = (Map<?, ?>) taskObj;
                if ("FAILED".equals(String.valueOf(task.get("state")))) {
                    return new ConnectorStatus("FAILED", String.valueOf(task.get("trace")));
                }
            }
        }
        return new ConnectorStatus(connectorState, null);
    }

    /**
     * POST /connectors/{name}/restart?includeTasks=true&onlyFailed=true -
     * restarts just the connector's currently-failed task(s) (and the
     * connector instance itself if that's what's FAILED), without a full
     * config redeploy. Distinct from deploy(), which is the heavier
     * "recreate from scratch" path used by the manual "启动" button.
     * Thin passthrough like pause()/resume()/delete() - does not catch or
     * wrap exceptions; CdcSourceStatusScheduler treats calls to this as
     * best-effort and is responsible for not letting failures here escape
     * the polling loop.
     */
    public void restart(String connectorName) {
        restTemplate.postForObject(baseUrl + "/connectors/" + connectorName + "/restart?includeTasks=true&onlyFailed=true", null, Void.class);
    }

    /** GET /connectors returns a plain JSON array of connector name strings. */
    public List<String> listConnectorNames() {
        String[] result;
        try {
            result = restTemplate.getForObject(baseUrl + "/connectors", String[].class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Kafka Connect：" + exception.getMessage());
        }
        return result == null ? List.of() : List.of(result);
    }

    public void pause(String connectorName) {
        restTemplate.put(baseUrl + "/connectors/" + connectorName + "/pause", null);
    }

    public void resume(String connectorName) {
        restTemplate.put(baseUrl + "/connectors/" + connectorName + "/resume", null);
    }

    public void delete(String connectorName) {
        restTemplate.delete(baseUrl + "/connectors/" + connectorName);
    }

    public record ConnectorStatus(String state, String message) {
    }
}
