package com.company.dataops.console.service.flink;

import com.company.dataops.console.entity.FlinkClusterEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class KubernetesFlinkOperatorClient {
    private static final String API_PATH = "/apis/flink.apache.org/v1beta1/namespaces/%s/flinkdeployments";

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper;

    public KubernetesFlinkOperatorClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> apply(FlinkClusterEntity cluster, DeploymentRequest deployment) {
        requireOperator(cluster);
        Map<String, Object> resource = resource(cluster, deployment);
        String url = collectionUrl(cluster) + "/" + encode(deployment.name()) + "?fieldManager=data-processing-platform&force=true";
        return exchange(cluster, HttpRequest.newBuilder(URI.create(url))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(write(resource)))
            .header("Content-Type", "application/apply-patch+yaml")
            .timeout(Duration.ofSeconds(30)));
    }

    public Map<String, Object> get(FlinkClusterEntity cluster, String name) {
        requireOperator(cluster);
        return exchange(cluster, HttpRequest.newBuilder(URI.create(collectionUrl(cluster) + "/" + encode(name)))
            .GET().timeout(Duration.ofSeconds(15)));
    }

    public Map<String, Object> delete(FlinkClusterEntity cluster, String name) {
        requireOperator(cluster);
        return exchange(cluster, HttpRequest.newBuilder(URI.create(collectionUrl(cluster) + "/" + encode(name)))
            .DELETE().timeout(Duration.ofSeconds(30)));
    }

    private Map<String, Object> resource(FlinkClusterEntity cluster, DeploymentRequest request) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jarURI", request.jarUri());
        job.put("parallelism", request.parallelism() == null ? 1 : request.parallelism());
        job.put("upgradeMode", request.upgradeMode() == null ? "savepoint" : request.upgradeMode());
        job.put("state", request.state() == null ? "running" : request.state());
        if (request.entryClass() != null && !request.entryClass().isBlank()) job.put("entryClass", request.entryClass());
        if (request.args() != null && !request.args().isEmpty()) job.put("args", request.args());
        if (request.initialSavepointPath() != null && !request.initialSavepointPath().isBlank()) {
            job.put("initialSavepointPath", request.initialSavepointPath());
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("image", request.image() == null || request.image().isBlank() ? cluster.getFlinkImage() : request.image());
        spec.put("flinkVersion", request.flinkVersion() == null ? "v1_20" : request.flinkVersion());
        spec.put("serviceAccount", cluster.getServiceAccount() == null ? "flink" : cluster.getServiceAccount());
        spec.put("flinkConfiguration", request.flinkConfiguration() == null ? Map.of() : request.flinkConfiguration());
        spec.put("jobManager", Map.of("resource", Map.of("memory", "2048m", "cpu", 1)));
        spec.put("taskManager", Map.of("resource", Map.of("memory", "2048m", "cpu", 1)));
        spec.put("job", job);

        return Map.of(
            "apiVersion", "flink.apache.org/v1beta1",
            "kind", "FlinkDeployment",
            "metadata", Map.of("name", request.name(), "namespace", cluster.getKubeNamespace()),
            "spec", spec
        );
    }

    private Map<String, Object> exchange(FlinkClusterEntity cluster, HttpRequest.Builder builder) {
        String token = resolveToken(cluster);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.header("Accept", "application/json");
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = response.body() == null || response.body().isBlank()
                ? Map.of("statusCode", response.statusCode())
                : objectMapper.readValue(response.body(), new TypeReference<>() { });
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Kubernetes Operator 请求失败（HTTP " + response.statusCode() + "）：" + body.getOrDefault("message", "未知错误"));
            }
            return body;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Kubernetes API：" + exception.getMessage());
        }
    }

    private String collectionUrl(FlinkClusterEntity cluster) {
        String api = cluster.getKubeApiUrl().replaceAll("/+$", "");
        return api + API_PATH.formatted(encode(cluster.getKubeNamespace()));
    }

    private String resolveToken(FlinkClusterEntity cluster) {
        if (cluster.getKubeTokenEnv() == null || cluster.getKubeTokenEnv().isBlank()) return null;
        String token = System.getenv(cluster.getKubeTokenEnv());
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Kubernetes 访问令牌环境变量未配置：" + cluster.getKubeTokenEnv());
        }
        return token;
    }

    private void requireOperator(FlinkClusterEntity cluster) {
        if (!"KUBERNETES_OPERATOR".equals(cluster.getDeploymentMode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该集群不是 Kubernetes Operator 模式");
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成 FlinkDeployment 失败");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record DeploymentRequest(
        String name,
        String jarUri,
        String entryClass,
        List<String> args,
        Integer parallelism,
        String image,
        String flinkVersion,
        String upgradeMode,
        String state,
        String initialSavepointPath,
        Map<String, String> flinkConfiguration
    ) { }
}
