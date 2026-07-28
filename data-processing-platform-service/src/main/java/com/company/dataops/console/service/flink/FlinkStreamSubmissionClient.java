package com.company.dataops.console.service.flink;

import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.service.storage.JarStorageService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Talks to the same Flink standalone session cluster data-processing-platform-service's
 * FlinkRestClient submits batch jars to (docker/bigdata's flink-jobmanager,
 * REST port 18082) - Flink's REST API doesn't distinguish batch vs streaming
 * at the cluster level, that's a property of what the jar's main() does.
 * This client adds what a long-running streaming job needs on top of the
 * plain upload+run+status flow: per-job checkpoint/restart-strategy config,
 * and stop-with-savepoint so a later start can resume from where it left off.
 */
@Component
public class FlinkStreamSubmissionClient {
    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;
    private final FlinkCapacityInspector capacityInspector;
    private final JarStorageService jarStorageService;

    public FlinkStreamSubmissionClient(
        @Value("${platform.bigdata.flink-rest-url:http://localhost:18082}") String baseUrl,
        FlinkCapacityInspector capacityInspector,
        JarStorageService jarStorageService
    ) {
        this.baseUrl = baseUrl;
        this.capacityInspector = capacityInspector;
        this.jarStorageService = jarStorageService;
    }

    public String submit(FlinkStreamJobEntity job) {
        if (job.getJarPath() == null || job.getJarPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Flink 流作业必须填写 JAR 路径");
        }
        capacityInspector.requireCapacity(job.getParallelism() == null ? 1 : job.getParallelism());
        String jarId = uploadJar(job.getJarPath());
        return run(jarId, job);
    }

    private String uploadJar(String jarStorageKey) {
        byte[] jarBytes = jarStorageService.get(jarStorageKey);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("jarfile", new ByteArrayResource(jarBytes) {
            @Override
            public String getFilename() {
                return jarStorageKey;
            }
        }, MediaType.parseMediaType("application/x-java-archive"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, org.springframework.http.HttpEntity<?>>> request = new HttpEntity<>(builder.build(), headers);

        Map<?, ?> result;
        try {
            result = restTemplate.postForObject(baseUrl + "/v1/jars/upload", request, Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Flink JobManager：" + exception.getMessage());
        }
        if (result == null || !"success".equals(result.get("status"))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Flink JAR 上传失败");
        }
        String filename = String.valueOf(result.get("filename"));
        return filename.substring(filename.lastIndexOf('/') + 1);
    }

    private String run(String jarId, FlinkStreamJobEntity job) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (job.getEntryClass() != null && !job.getEntryClass().isBlank()) {
            body.put("entryClass", job.getEntryClass());
        }
        if (job.getProgramArgs() != null && !job.getProgramArgs().isBlank()) {
            body.put("programArgs", job.getProgramArgs());
        }
        body.put("parallelism", job.getParallelism() == null ? 1 : job.getParallelism());
        if (job.getSavepointPath() != null && !job.getSavepointPath().isBlank()) {
            body.put("savepointPath", job.getSavepointPath());
        }
        body.put("flinkConfiguration", buildFlinkConfiguration(job));

        Map<?, ?> result;
        try {
            result = restTemplate.postForObject(baseUrl + "/v1/jars/" + jarId + "/run", body, Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Flink 流作业启动失败：" + exception.getMessage());
        }
        if (result == null || result.get("jobid") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Flink 流作业启动失败：无响应");
        }
        return String.valueOf(result.get("jobid"));
    }

    private Map<String, String> buildFlinkConfiguration(FlinkStreamJobEntity job) {
        Map<String, String> config = new LinkedHashMap<>();
        int checkpointIntervalMs = job.getCheckpointIntervalMs() == null ? 10000 : job.getCheckpointIntervalMs();
        config.put("execution.checkpointing.interval", checkpointIntervalMs + " ms");
        if ("FIXED_DELAY".equalsIgnoreCase(job.getRestartStrategy())) {
            int attempts = job.getRestartAttempts() == null ? 3 : job.getRestartAttempts();
            int delaySeconds = job.getRestartDelaySeconds() == null ? 10 : job.getRestartDelaySeconds();
            config.put("restart-strategy", "fixed-delay");
            config.put("restart-strategy.fixed-delay.attempts", String.valueOf(attempts));
            config.put("restart-strategy.fixed-delay.delay", delaySeconds + " s");
        } else {
            config.put("restart-strategy", "none");
        }
        return config;
    }

    /**
     * Used by start() (both jar and SQL jobs) to avoid submitting a duplicate
     * instance of a job that's already running on the cluster - confirmed
     * live: calling start() on a job whose old instance was still healthy
     * but the cluster had no free slot for a second one clobbered the DB
     * status to FAILED, even though the original instance kept running fine
     * the whole time. Deliberately stricter than status() above: that method
     * optimistically reports RUNNING on a transient network/lookup failure
     * (right for periodic health-refresh polling, where flip-flopping on a
     * blip is worse than a stale read), but here a false positive would
     * silently skip starting a job that's actually not running - so
     * anything other than a confirmed live state returns false and start()
     * falls through to its normal submit-a-fresh-instance path unchanged.
     */
    public boolean isRunning(String flinkJobId) {
        try {
            Map<?, ?> result = restTemplate.getForObject(baseUrl + "/v1/jobs/" + flinkJobId, Map.class);
            if (result == null || result.get("state") == null) {
                return false;
            }
            String state = String.valueOf(result.get("state"));
            return switch (state) {
                case "RUNNING", "RESTARTING", "CREATED", "RECONCILING" -> true;
                default -> false;
            };
        } catch (Exception exception) {
            return false;
        }
    }

    public FlinkJobStatus status(String flinkJobId) {
        Map<?, ?> result;
        try {
            result = restTemplate.getForObject(baseUrl + "/v1/jobs/" + flinkJobId, Map.class);
        } catch (Exception exception) {
            return new FlinkJobStatus("RUNNING", "查询状态失败，稍后重试：" + exception.getMessage());
        }
        if (result == null || result.get("state") == null) {
            return new FlinkJobStatus("RUNNING", "状态未知，继续等待");
        }
        String state = String.valueOf(result.get("state"));
        return switch (state) {
            case "RUNNING", "RESTARTING", "CREATED", "RECONCILING" -> new FlinkJobStatus("RUNNING", "运行中：" + state);
            case "FAILED", "FAILING" -> new FlinkJobStatus("FAILED", "作业失败（" + state + "）");
            case "CANCELED", "CANCELLING" -> new FlinkJobStatus("CANCELED", "已停止");
            case "FINISHED" -> new FlinkJobStatus("FINISHED", "作业已结束（流作业正常不会到这个状态，可能是数据源提前结束）");
            default -> new FlinkJobStatus("RUNNING", "运行中：" + state);
        };
    }

    /**
     * Stop-with-savepoint is Flink's own two-step async operation: trigger it,
     * then poll for completion. Doing the whole thing here (rather than making
     * the controller deal with trigger ids) mirrors how the offline side's
     * DataTaskExecutionService hides its own "submit then poll" mechanics
     * behind a single blocking call for the caller.
     */
    public String stopWithSavepoint(String flinkJobId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drain", false);

        Map<?, ?> triggerResult;
        try {
            triggerResult = restTemplate.postForObject(baseUrl + "/v1/jobs/" + flinkJobId + "/stop", body, Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "触发停止失败：" + exception.getMessage());
        }
        if (triggerResult == null || triggerResult.get("request-id") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "触发停止失败：无响应");
        }
        String triggerId = String.valueOf(triggerResult.get("request-id"));

        for (int attempt = 0; attempt < 30; attempt++) {
            sleepOneSecond();
            Map<?, ?> statusResult;
            try {
                statusResult = restTemplate.getForObject(baseUrl + "/v1/jobs/" + flinkJobId + "/savepoints/" + triggerId, Map.class);
            } catch (Exception exception) {
                continue;
            }
            if (statusResult == null) {
                continue;
            }
            Map<?, ?> status = (Map<?, ?>) statusResult.get("status");
            String statusId = status == null ? null : String.valueOf(status.get("id"));
            if ("COMPLETED".equals(statusId)) {
                Map<?, ?> operation = (Map<?, ?>) statusResult.get("operation");
                if (operation != null && operation.get("location") != null) {
                    return String.valueOf(operation.get("location"));
                }
                String failureCause = operation == null ? "无详情" : String.valueOf(operation.get("failure-cause"));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "停止失败：" + failureCause);
            }
        }
        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "等待停止确认超时（30秒）");
    }

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public record FlinkJobStatus(String state, String message) {
    }
}
