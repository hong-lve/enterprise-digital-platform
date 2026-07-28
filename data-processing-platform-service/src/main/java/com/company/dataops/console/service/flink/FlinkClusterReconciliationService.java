package com.company.dataops.console.service.flink;

import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Finds Flink cluster jobs that don't correspond to any known
 * flink_stream_job/flink_sql_job row - e.g. a row deleted while the cluster
 * was unreachable (delete()'s stopWithSavepoint() is best-effort and swallows
 * that case), or a job submitted straight to Flink outside this platform.
 * These silently hold a task slot forever with no UI anywhere showing they
 * exist, which is exactly what made diagnosing "集群可用 slot 不足" errors
 * during this session's testing require manually curl-ing Flink's REST API.
 * Only FINISHED/CANCELED/FAILED jobs are excluded - those don't hold slots,
 * so surfacing them as "orphaned" would just be noise.
 */
@Component
public class FlinkClusterReconciliationService {
    private static final Set<String> ACTIVE_STATES = Set.of("RUNNING", "RESTARTING", "CREATED", "RECONCILING");

    // The default SimpleClientHttpRequestFactory (plain HttpURLConnection)
    // rejects PATCH outright ("Invalid HTTP method: PATCH") - confirmed live
    // when cancel() first tried it against a genuine test job. Every other
    // Flink call in this codebase only needs GET/POST, which is why this is
    // the first client here to hit the issue.
    private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());
    private final String baseUrl;
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkSqlJobMapper flinkSqlJobMapper;

    public FlinkClusterReconciliationService(
        @Value("${platform.bigdata.flink-rest-url:http://localhost:18082}") String baseUrl,
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkSqlJobMapper flinkSqlJobMapper
    ) {
        this.baseUrl = baseUrl;
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkSqlJobMapper = flinkSqlJobMapper;
    }

    public List<OrphanedJobView> listOrphanedJobs() {
        Set<String> knownJobIds = new HashSet<>();
        flinkStreamJobMapper.selectList(null).stream()
            .map(FlinkStreamJobEntity::getFlinkJobId)
            .filter(jobId -> jobId != null)
            .forEach(knownJobIds::add);
        flinkSqlJobMapper.selectList(null).stream()
            .map(FlinkSqlJobEntity::getFlinkJobId)
            .filter(jobId -> jobId != null)
            .forEach(knownJobIds::add);

        return activeClusterJobs().stream()
            .filter(job -> !knownJobIds.contains(job.jobId()))
            .toList();
    }

    private List<OrphanedJobView> activeClusterJobs() {
        Map<?, ?> result;
        try {
            result = restTemplate.getForObject(baseUrl + "/jobs/overview", Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Flink JobManager：" + exception.getMessage());
        }
        if (result == null || result.get("jobs") == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) result.get("jobs");
        return jobs.stream()
            .filter(job -> ACTIVE_STATES.contains(String.valueOf(job.get("state"))))
            .map(job -> new OrphanedJobView(
                String.valueOf(job.get("jid")),
                String.valueOf(job.get("name")),
                String.valueOf(job.get("state")),
                ((Number) job.get("start-time")).longValue()
            ))
            .toList();
    }

    // No-savepoint cancel (not stopWithSavepoint): an orphaned job has no DB
    // row to record a savepoint path against, so there'd be nothing to ever
    // resume it from - the only goal here is reclaiming the slot.
    public void cancel(String flinkJobId) {
        try {
            restTemplate.exchange(baseUrl + "/jobs/" + flinkJobId + "?mode=cancel", HttpMethod.PATCH, HttpEntity.EMPTY, Void.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "取消作业失败：" + exception.getMessage());
        }
    }

    public record OrphanedJobView(String jobId, String name, String state, long startTime) {
    }
}
