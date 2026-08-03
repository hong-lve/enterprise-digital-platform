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
 *
 * Also reserves a configurable slot floor for PROD (flink-prod-reserved-slots,
 * default 0/disabled) - without real per-namespace physical isolation (see
 * the reliability roadmap's item 3: this project deliberately stayed on one
 * shared standalone cluster rather than standing up a second local K8s
 * cluster on already resource-constrained hardware), a non-PROD job could
 * otherwise consume every slot and leave PROD jobs unable to recover from a
 * restart. A reserved floor gives the same practical guarantee a namespace
 * quota would, without the infrastructure migration.
 */
@Component
public class FlinkCapacityInspector {
    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;
    private final int reservedSlotsForProd;

    public FlinkCapacityInspector(
        @Value("${platform.bigdata.flink-rest-url:http://localhost:18082}") String baseUrl,
        @Value("${platform.bigdata.flink-prod-reserved-slots:0}") int reservedSlotsForProd
    ) {
        this.baseUrl = baseUrl;
        this.reservedSlotsForProd = reservedSlotsForProd;
    }

    public void requireCapacity(int parallelism, String environment) {
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
        if (reservedSlotsForProd > 0 && !"PROD".equals(environment)) {
            int slotsRemainingAfter = slotsAvailable - parallelism;
            if (slotsRemainingAfter < reservedSlotsForProd) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "非生产环境作业不能占用生产环境预留容量：需要为生产环境保留至少 " + reservedSlotsForProd + " 个 slot，"
                        + "当前集群共有 " + slotsAvailable + " 个可用 slot，本次申请 " + parallelism + " 个后将只剩 " + slotsRemainingAfter + " 个");
            }
        }
    }
}
