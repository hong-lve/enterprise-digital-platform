package com.company.dataops.console.service.flink;

import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.entity.FlinkClusterEntity;
import com.company.dataops.console.service.storage.JarStorageService;
import com.company.dataops.console.service.monitoring.RealtimeMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.web.client.HttpClientErrorException;
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
    private final RealtimeMetrics metrics;
    private final FlinkClusterRegistryService clusterRegistryService;

    public FlinkStreamSubmissionClient(
        @Value("${platform.bigdata.flink-rest-url:http://localhost:18082}") String baseUrl,
        FlinkCapacityInspector capacityInspector,
        JarStorageService jarStorageService,
        RealtimeMetrics metrics,
        FlinkClusterRegistryService clusterRegistryService
    ) {
        this.baseUrl = baseUrl;
        this.capacityInspector = capacityInspector;
        this.jarStorageService = jarStorageService;
        this.metrics = metrics;
        this.clusterRegistryService = clusterRegistryService;
    }

    public String submit(FlinkStreamJobEntity job) {
        if (job.getJarPath() == null || job.getJarPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Flink 流作业必须填写 JAR 路径");
        }
        FlinkClusterEntity cluster = clusterRegistryService.resolve(job.getClusterId(), job.getEnvironment());
        if (!"STANDALONE".equals(cluster.getDeploymentMode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该作业选择的是 Operator 集群，请使用 Operator 部署接口");
        }
        String targetBaseUrl = cluster.getRestUrl();
        capacityInspector.requireCapacity(targetBaseUrl, job.getParallelism() == null ? 1 : job.getParallelism(), job.getEnvironment());
        try {
            String jarId = uploadJar(targetBaseUrl, job.getJarPath());
            String jobId = run(targetBaseUrl, jarId, job);
            metrics.jobSubmission("jar", true);
            return clusterRegistryService.encodeJobId(cluster, jobId);
        } catch (RuntimeException exception) {
            metrics.jobSubmission("jar", false);
            throw exception;
        }
    }

    /**
     * Flink keeps every jar ever uploaded to it and can re-run one by id
     * without a fresh upload, so an already-present jar makes the whole
     * download-from-object-storage + upload-to-Flink round trip pure waste.
     * That round trip is not a micro-optimisation to skip: object storage and
     * Flink both live on the remote host while this service runs locally, so
     * a 27MB jar crosses a ~1Mbps link twice (~7 minutes) and reliably blew
     * past JarStorageService.get()'s read deadline - starting an existing job
     * failed outright until this lookup existed.
     *
     * Matches on Flink's `name` (the original upload filename, which is the
     * storage key) rather than `id` (`<uuid>_<name>`, assigned per upload).
     * Any lookup failure falls through to the upload path: a stale hit is the
     * only real hazard, and jar content is immutable per storage key here
     * (JarStorageService writes a fresh uuid key per version).
     */
    private String findExistingJarId(String targetBaseUrl, String jarStorageKey) {
        Map<?, ?> result;
        try {
            result = restTemplate.getForObject(targetBaseUrl + "/v1/jars", Map.class);
        } catch (Exception exception) {
            return null;
        }
        if (result == null || !(result.get("files") instanceof List<?> files)) {
            return null;
        }
        String newestId = null;
        long newestUploaded = Long.MIN_VALUE;
        for (Object fileObj : files) {
            if (!(fileObj instanceof Map<?, ?> file)) {
                continue;
            }
            if (!jarStorageKey.equals(String.valueOf(file.get("name"))) || file.get("id") == null) {
                continue;
            }
            Long uploaded = toLong(file.get("uploaded"));
            long uploadedAt = uploaded == null ? 0L : uploaded;
            if (uploadedAt >= newestUploaded) {
                newestUploaded = uploadedAt;
                newestId = String.valueOf(file.get("id"));
            }
        }
        return newestId;
    }

    private String uploadJar(String targetBaseUrl, String jarStorageKey) {
        String existingJarId = findExistingJarId(targetBaseUrl, jarStorageKey);
        if (existingJarId != null) {
            return existingJarId;
        }
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
            result = restTemplate.postForObject(targetBaseUrl + "/v1/jars/upload", request, Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Flink JobManager：" + exception.getMessage());
        }
        if (result == null || !"success".equals(result.get("status"))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Flink JAR 上传失败");
        }
        String filename = String.valueOf(result.get("filename"));
        return filename.substring(filename.lastIndexOf('/') + 1);
    }

    private String run(String targetBaseUrl, String jarId, FlinkStreamJobEntity job) {
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
            result = restTemplate.postForObject(targetBaseUrl + "/v1/jars/" + jarId + "/run", body, Map.class);
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
        int checkpointIntervalMs = job.getCheckpointIntervalMs() == null ? 60000 : job.getCheckpointIntervalMs();
        config.put("execution.checkpointing.interval", checkpointIntervalMs + " ms");
        // Checkpoint governance knobs - all optional on the entity (nullable
        // columns, see V33 migration), defaulted here rather than at the DB
        // level so a job created before this feature existed keeps behaving
        // exactly like Flink's own out-of-the-box defaults.
        int checkpointTimeoutMs = job.getCheckpointTimeoutMs() == null ? 600000 : job.getCheckpointTimeoutMs();
        config.put("execution.checkpointing.timeout", checkpointTimeoutMs + " ms");
        int minPauseMs = job.getMinPauseBetweenCheckpointsMs() == null ? 0 : job.getMinPauseBetweenCheckpointsMs();
        config.put("execution.checkpointing.min-pause", minPauseMs + " ms");
        int maxConcurrent = job.getMaxConcurrentCheckpoints() == null ? 1 : job.getMaxConcurrentCheckpoints();
        config.put("execution.checkpointing.max-concurrent-checkpoints", String.valueOf(maxConcurrent));
        int tolerableFailed = job.getTolerableFailedCheckpoints() == null ? 0 : job.getTolerableFailedCheckpoints();
        config.put("execution.checkpointing.tolerable-failed-checkpoints", String.valueOf(tolerableFailed));
        String mode = job.getCheckpointingMode() == null || job.getCheckpointingMode().isBlank()
            ? "EXACTLY_ONCE" : job.getCheckpointingMode();
        config.put("execution.checkpointing.mode", mode);
        String externalizedRetention = job.getExternalizedCheckpointRetention() == null || job.getExternalizedCheckpointRetention().isBlank()
            ? "RETAIN_ON_CANCELLATION" : job.getExternalizedCheckpointRetention();
        config.put("execution.checkpointing.externalized-checkpoint-retention", externalizedRetention);
        config.put("execution.checkpointing.unaligned.enabled", String.valueOf(Boolean.TRUE.equals(job.getUnalignedCheckpointsEnabled())));
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
        FlinkClusterRegistryService.RoutedJob routed = clusterRegistryService.routeJobId(flinkJobId);
        try {
            Map<?, ?> result = restTemplate.getForObject(routed.cluster().getRestUrl() + "/v1/jobs/" + routed.actualJobId(), Map.class);
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
        FlinkClusterRegistryService.RoutedJob routed = clusterRegistryService.routeJobId(flinkJobId);
        Map<?, ?> result;
        try {
            result = restTemplate.getForObject(routed.cluster().getRestUrl() + "/v1/jobs/" + routed.actualJobId(), Map.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            // Flink itself responded and definitively said this job id doesn't
            // exist - not a transient call failure, so don't fall back to
            // "RUNNING, retry later" like the catch-all below. That fallback
            // exists for actual transient errors (connection refused, Flink
            // briefly unreachable), but a 404 here never resolves itself on
            // retry - it means Flink's job state was wiped (e.g. a full
            // flink-jobmanager restart), and every subsequent poll would 404
            // forever, leaving the DB stuck showing "RUNNING" indefinitely.
            return new FlinkJobStatus("FAILED", "作业在 Flink 侧已不存在（Flink 可能已重启，作业状态丢失）");
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
     * GET /v1/jobs/:id/checkpoints - Flink's own bounded (history-size-limited,
     * default last 10) checkpoint history for a job, source of truth for both
     * the checkpoint size/duration/failure trend view and the savepoint
     * inventory (entries where checkpointType is SAVEPOINT/SYNC_SAVEPOINT and
     * externalPath is set) - see FlinkCheckpointHistoryScheduler, which polls
     * this and persists it to flink_checkpoint_history since Flink itself
     * doesn't retain history beyond its own in-memory bound.
     */
    public List<CheckpointRecord> checkpointHistory(String flinkJobId) {
        FlinkClusterRegistryService.RoutedJob routed = clusterRegistryService.routeJobId(flinkJobId);
        Map<?, ?> result;
        try {
            result = restTemplate.getForObject(routed.cluster().getRestUrl() + "/v1/jobs/" + routed.actualJobId() + "/checkpoints", Map.class);
        } catch (Exception exception) {
            return List.of();
        }
        Object historyObj = result == null ? null : result.get("history");
        if (!(historyObj instanceof List<?> history)) {
            return List.of();
        }
        List<CheckpointRecord> records = new ArrayList<>();
        for (Object entryObj : history) {
            Map<?, ?> entry = (Map<?, ?>) entryObj;
            records.add(new CheckpointRecord(
                toLong(entry.get("id")),
                String.valueOf(entry.get("status")),
                String.valueOf(entry.get("checkpoint_type")),
                toLong(entry.get("trigger_timestamp")),
                toLong(entry.get("latest_ack_timestamp")),
                toLong(entry.get("end_to_end_duration")),
                toLong(entry.get("state_size")),
                entry.get("external_path") == null ? null : String.valueOf(entry.get("external_path")),
                entry.get("failure_message") == null ? null : String.valueOf(entry.get("failure_message"))
            ));
        }
        return records;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record CheckpointRecord(
        Long checkpointId,
        String status,
        String checkpointType,
        Long triggerTimestamp,
        Long latestAckTimestamp,
        Long endToEndDurationMs,
        Long stateSizeBytes,
        String externalPath,
        String failureMessage
    ) {
    }

    /**
     * POST /v1/savepoint-disposal - same trigger-then-poll async pattern as
     * stopWithSavepoint() below (Flink's REST API uses this pattern uniformly
     * for any operation that runs on the JobManager rather than completing
     * synchronously). Used by FlinkSavepointRetentionScheduler and the manual
     * "删除保存点" action - deletes the actual file via Flink's own process
     * (which has filesystem access to state.savepoints.dir), since this
     * service's JVM doesn't and the savepoint volume isn't host-mounted.
     */
    public void disposeSavepoint(String savepointPath) {
        disposeSavepoint(savepointPath, null);
    }

    public void disposeSavepoint(String savepointPath, String flinkJobId) {
        String targetBaseUrl = flinkJobId == null ? baseUrl : clusterRegistryService.routeJobId(flinkJobId).cluster().getRestUrl();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("savepoint-path", savepointPath);

        Map<?, ?> triggerResult;
        try {
            triggerResult = restTemplate.postForObject(targetBaseUrl + "/v1/savepoint-disposal", body, Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "触发保存点删除失败：" + exception.getMessage());
        }
        if (triggerResult == null || triggerResult.get("request-id") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "触发保存点删除失败：无响应");
        }
        String triggerId = String.valueOf(triggerResult.get("request-id"));

        for (int attempt = 0; attempt < 30; attempt++) {
            sleepOneSecond();
            Map<?, ?> statusResult;
            try {
                statusResult = restTemplate.getForObject(targetBaseUrl + "/v1/savepoint-disposal/" + triggerId, Map.class);
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
                Object failureCause = operation == null ? null : operation.get("failure-cause");
                if (failureCause != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "保存点删除失败：" + describeFailureCause(failureCause));
                }
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "等待保存点删除确认超时（30秒）");
    }

    /**
     * Stop-with-savepoint is Flink's own two-step async operation: trigger it,
     * then poll for completion. Doing the whole thing here (rather than making
     * the controller deal with trigger ids) mirrors how the offline side's
     * DataTaskExecutionService hides its own "submit then poll" mechanics
     * behind a single blocking call for the caller.
     */
    public String stopWithSavepoint(String flinkJobId) {
        FlinkClusterRegistryService.RoutedJob routed = clusterRegistryService.routeJobId(flinkJobId);
        String targetBaseUrl = routed.cluster().getRestUrl();
        String actualJobId = routed.actualJobId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drain", false);

        Map<?, ?> triggerResult;
        try {
            triggerResult = restTemplate.postForObject(targetBaseUrl + "/v1/jobs/" + actualJobId + "/stop", body, Map.class);
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
                statusResult = restTemplate.getForObject(targetBaseUrl + "/v1/jobs/" + actualJobId + "/savepoints/" + triggerId, Map.class);
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
                String failureCause = operation == null ? "无详情" : describeFailureCause(operation.get("failure-cause"));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "停止失败：" + failureCause);
            }
        }
        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "等待停止确认超时（30秒）");
    }

    /**
     * A graceful stop-with-savepoint requires the job to be able to complete
     * a checkpoint, which a job crash-looping (e.g. the Oracle Avro
     * nullable-union incompatibility) generally can't - the operator's
     * "click stop, have it actually stop" expectation doesn't care about
     * that nuance, so fall back to an unconditional cancel (no savepoint,
     * but it works regardless of task state) whenever the graceful path
     * fails, instead of surfacing the failure and making them retry by hand.
     */
    public String stopOrCancel(String flinkJobId) {
        try {
            return stopWithSavepoint(flinkJobId);
        } catch (ResponseStatusException exception) {
            cancel(flinkJobId);
            return null;
        }
    }

    /**
     * Flink's REST API cancel is PATCH /jobs/:id?mode=cancel - RestTemplate's
     * default HttpURLConnection-backed factory can't send PATCH at all
     * (JDK limitation), and this project deliberately avoids pulling in
     * Apache HttpClient5 here (see pom.xml's S3Client comment: Spring Boot's
     * managed httpclient5 version is incompatible with this project's other
     * pinned versions). java.net.http.HttpClient (built into the JDK since
     * 11) supports arbitrary methods natively, so use it for just this call.
     */
    private void cancel(String flinkJobId) {
        FlinkClusterRegistryService.RoutedJob routed = clusterRegistryService.routeJobId(flinkJobId);
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(routed.cluster().getRestUrl() + "/v1/jobs/" + routed.actualJobId() + "?mode=cancel"))
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.noBody())
                .timeout(java.time.Duration.ofSeconds(15))
                .build();
            java.net.http.HttpResponse<Void> response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "强制取消失败：HTTP " + response.statusCode());
            }
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "强制取消失败：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "强制取消被中断");
        }
    }

    /**
     * Flink's failure-cause field is a structured object
     * ({class, stack-trace, serialized-throwable}), not a plain string - a
     * bare String.valueOf(...) on it dumps the raw Map.toString(), which
     * includes the base64 serialized-throwable blob (hundreds of chars) in
     * the user-facing error message. Surface just the readable class + first
     * stack-trace line instead.
     */
    private String describeFailureCause(Object failureCause) {
        if (!(failureCause instanceof Map<?, ?> causeMap)) {
            return failureCause == null ? "无详情" : String.valueOf(failureCause);
        }
        Object stackTrace = causeMap.get("stack-trace");
        if (stackTrace instanceof String stackTraceText && !stackTraceText.isBlank()) {
            int newline = stackTraceText.indexOf('\n');
            return newline == -1 ? stackTraceText : stackTraceText.substring(0, newline);
        }
        Object clazz = causeMap.get("class");
        return clazz == null ? "无详情" : String.valueOf(clazz);
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
