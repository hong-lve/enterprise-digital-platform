package com.company.dataops.console.service.flink;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rejects a submission before it touches Flink at all if the cluster
 * doesn't currently have enough free task slots for it - otherwise Flink's
 * REST API happily accepts the jar+run request and the job just sits
 * unscheduled (or worse, partially schedules and starves whatever else
 * needed those slots). Confirmed live against the local cluster
 * (GET /overview -> {"slots-total":2,"slots-available":0,...}) that this
 * is a real, not hypothetical, situation with the 2-slot local
 * docker-compose taskmanager.
 */
@Component
public class FlinkCapacityInspector {
    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    public FlinkCapacityInspector(@Value("${platform.bigdata.flink-rest-url:http://localhost:18082}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void requireCapacity(int parallelism) {
        Map<?, ?> result;
        try {
            result = restTemplate.getForObject(baseUrl + "/overview", Map.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 Flink JobManager 检查集群容量：" + exception.getMessage());
        }
        if (result == null || result.get("slots-available") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "查询 Flink 集群容量失败：无响应");
        }
        int slotsAvailable = ((Number) result.get("slots-available")).intValue();
        if (parallelism > slotsAvailable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "集群可用 slot 不足：作业需要 " + parallelism + " 个 slot，当前集群仅剩 " + slotsAvailable + " 个可用");
        }
    }
}
