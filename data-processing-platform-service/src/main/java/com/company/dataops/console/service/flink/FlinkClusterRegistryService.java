package com.company.dataops.console.service.flink;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.FlinkClusterEntity;
import com.company.dataops.console.mapper.FlinkClusterMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FlinkClusterRegistryService {
    private final FlinkClusterMapper mapper;
    private final String legacyRestUrl;
    private final String legacySqlGatewayUrl;

    public FlinkClusterRegistryService(
        FlinkClusterMapper mapper,
        @Value("${platform.bigdata.flink-rest-url:http://localhost:18082}") String legacyRestUrl,
        @Value("${platform.bigdata.flink-sql-gateway-url:http://localhost:18084}") String legacySqlGatewayUrl
    ) {
        this.mapper = mapper;
        this.legacyRestUrl = legacyRestUrl;
        this.legacySqlGatewayUrl = legacySqlGatewayUrl;
    }

    public FlinkClusterEntity resolve(Long clusterId, String environment) {
        if (clusterId != null) {
            FlinkClusterEntity cluster = mapper.selectById(clusterId);
            if (cluster == null || !Boolean.TRUE.equals(cluster.getEnabled())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "指定的 Flink 集群不存在或已禁用");
            }
            return cluster;
        }
        String env = environment == null || environment.isBlank() ? "DEV" : environment;
        List<FlinkClusterEntity> defaults = mapper.selectList(new LambdaQueryWrapper<FlinkClusterEntity>()
            .eq(FlinkClusterEntity::getEnvironment, env)
            .eq(FlinkClusterEntity::getEnabled, true)
            .eq(FlinkClusterEntity::getDefaultForEnvironment, true)
            .orderByAsc(FlinkClusterEntity::getId).last("LIMIT 1"));
        if (!defaults.isEmpty()) {
            return defaults.get(0);
        }
        return legacy(env);
    }

    private FlinkClusterEntity legacy(String environment) {
        FlinkClusterEntity legacy = new FlinkClusterEntity();
        legacy.setName("legacy-default");
        legacy.setEnvironment(environment);
        legacy.setDeploymentMode("STANDALONE");
        legacy.setRestUrl(legacyRestUrl);
        legacy.setSqlGatewayUrl(legacySqlGatewayUrl);
        legacy.setEnabled(true);
        return legacy;
    }

    public String encodeJobId(FlinkClusterEntity cluster, String actualJobId) {
        return cluster.getId() == null ? actualJobId : cluster.getId() + ":" + actualJobId;
    }

    public RoutedJob routeJobId(String storedJobId) {
        int separator = storedJobId == null ? -1 : storedJobId.indexOf(':');
        if (separator > 0 && storedJobId.substring(0, separator).chars().allMatch(Character::isDigit)) {
            Long clusterId = Long.valueOf(storedJobId.substring(0, separator));
            return new RoutedJob(resolve(clusterId, null), storedJobId.substring(separator + 1));
        }
        return new RoutedJob(legacy("DEV"), storedJobId);
    }

    public record RoutedJob(FlinkClusterEntity cluster, String actualJobId) {
    }
}
