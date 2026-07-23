package com.company.dataops.console.service.flink;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Estimates how backpressured a running Flink job is, using metrics Flink
 * 1.19 already returns from GET /jobs/{jobId} (confirmed live - each vertex
 * carries "accumulated-backpressured-time" alongside busy/idle time) rather
 * than the older thread-sampling-based /jobs/{jobId}/vertices/{vertexId}/
 * backpressure endpoint. Those are cumulative since the job started, which
 * isn't directly useful (a job that was briefly backpressured at startup
 * but has been healthy for hours would look permanently bad) - this tracks
 * the delta between polls to report "fraction of the last interval spent
 * backpressured" instead.
 *
 * Previous samples live in memory only (this component is a singleton
 * bean, polled every 15s by FlinkStreamJobPollingScheduler) - purely
 * transient working state, not worth a DB column. A service restart just
 * means the first poll after restart has nothing to diff against and is
 * skipped; the next one 15s later self-heals.
 */
@Component
public class FlinkBackpressureInspector {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlinkBackpressureInspector.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;
    private final Map<String, Map<String, Sample>> previousSamplesByJob = new ConcurrentHashMap<>();

    public FlinkBackpressureInspector(@Value("${platform.bigdata.flink-rest-url:http://localhost:18082}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Fraction (0.0-1.0) of the time since the last call spent backpressured, taking the worst vertex; null if this is the first sample for this job. */
    public Double currentRatio(String flinkJobId) {
        Map<String, Long> backpressuredTimeByVertex = fetchVertexBackpressuredTimes(flinkJobId);
        if (backpressuredTimeByVertex == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Map<String, Sample> previousSamples = previousSamplesByJob.computeIfAbsent(flinkJobId, id -> new ConcurrentHashMap<>());

        Double maxRatio = null;
        for (Map.Entry<String, Long> entry : backpressuredTimeByVertex.entrySet()) {
            String vertexId = entry.getKey();
            long currentBackpressuredMs = entry.getValue();
            Sample previous = previousSamples.get(vertexId);
            if (previous != null) {
                long deltaBackpressuredMs = currentBackpressuredMs - previous.backpressuredMs();
                long deltaWallClockMs = now - previous.sampledAtMs();
                if (deltaWallClockMs > 0) {
                    double ratio = Math.max(0.0, Math.min(1.0, deltaBackpressuredMs / (double) deltaWallClockMs));
                    if (maxRatio == null || ratio > maxRatio) {
                        maxRatio = ratio;
                    }
                }
            }
            previousSamples.put(vertexId, new Sample(currentBackpressuredMs, now));
        }
        return maxRatio;
    }

    /** Clears remembered samples for a job - call when a job stops/restarts so a stale delta from before a resubmission isn't compared against a new run. */
    public void forget(String flinkJobId) {
        previousSamplesByJob.remove(flinkJobId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> fetchVertexBackpressuredTimes(String flinkJobId) {
        Map<?, ?> result;
        try {
            result = restTemplate.getForObject(baseUrl + "/jobs/" + flinkJobId, Map.class);
        } catch (Exception exception) {
            LOGGER.warn("Failed to fetch job detail for backpressure check {}: {}", flinkJobId, exception.getMessage());
            return null;
        }
        if (result == null || result.get("vertices") == null) {
            return null;
        }
        List<?> vertices = (List<?>) result.get("vertices");
        Map<String, Long> times = new LinkedHashMap<>();
        for (Object vertexObj : vertices) {
            Map<?, ?> vertex = (Map<?, ?>) vertexObj;
            String vertexId = String.valueOf(vertex.get("id"));
            Map<?, ?> metrics = (Map<?, ?>) vertex.get("metrics");
            if (metrics == null || metrics.get("accumulated-backpressured-time") == null) {
                continue;
            }
            times.put(vertexId, ((Number) metrics.get("accumulated-backpressured-time")).longValue());
        }
        return times;
    }

    private record Sample(long backpressuredMs, long sampledAtMs) {
    }
}
