package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.FlinkClusterEntity;
import com.company.dataops.console.mapper.FlinkClusterMapper;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.service.flink.KubernetesFlinkOperatorClient;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/realtime/flink-clusters")
public class FlinkClusterController {
    private final FlinkClusterMapper mapper;
    private final KubernetesFlinkOperatorClient operatorClient;
    private final FlinkStreamJobMapper streamJobMapper;
    private final FlinkSqlJobMapper sqlJobMapper;

    public FlinkClusterController(FlinkClusterMapper mapper, KubernetesFlinkOperatorClient operatorClient,
                                  FlinkStreamJobMapper streamJobMapper, FlinkSqlJobMapper sqlJobMapper) {
        this.mapper = mapper;
        this.operatorClient = operatorClient;
        this.streamJobMapper = streamJobMapper;
        this.sqlJobMapper = sqlJobMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:flink-cluster:view')")
    public ApiResponse<List<FlinkClusterEntity>> list() {
        return ApiResponse.ok(mapper.selectList(new LambdaQueryWrapper<FlinkClusterEntity>().orderByAsc(FlinkClusterEntity::getEnvironment).orderByAsc(FlinkClusterEntity::getName)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('realtime:flink-cluster:manage')")
    @Transactional
    public ApiResponse<FlinkClusterEntity> create(@Valid @RequestBody FlinkClusterEntity cluster) {
        validate(cluster);
        if (cluster.getEnabled() == null) cluster.setEnabled(true);
        if (cluster.getDefaultForEnvironment() == null) cluster.setDefaultForEnvironment(false);
        clearPreviousDefault(cluster, null);
        mapper.insert(cluster);
        return ApiResponse.ok(cluster);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:flink-cluster:manage')")
    @Transactional
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody FlinkClusterEntity cluster) {
        validate(cluster);
        requireCluster(id);
        cluster.setId(id);
        clearPreviousDefault(cluster, id);
        mapper.updateById(cluster);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:flink-cluster:manage')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireCluster(id);
        long references = streamJobMapper.selectCount(new LambdaQueryWrapper<FlinkStreamJobEntity>()
            .eq(FlinkStreamJobEntity::getClusterId, id))
            + sqlJobMapper.selectCount(new LambdaQueryWrapper<FlinkSqlJobEntity>()
            .eq(FlinkSqlJobEntity::getClusterId, id));
        if (references > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该集群仍被 " + references + " 个作业引用，请先迁移作业");
        }
        mapper.deleteById(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/operator/deployments")
    @PreAuthorize("hasAuthority('realtime:flink-cluster:manage')")
    public ApiResponse<Map<String, Object>> applyDeployment(
        @PathVariable Long id,
        @RequestBody KubernetesFlinkOperatorClient.DeploymentRequest deployment
    ) {
        if (deployment.name() == null || deployment.name().isBlank() || deployment.jarUri() == null || deployment.jarUri().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部署名称和 JAR URI 不能为空");
        }
        return ApiResponse.ok(operatorClient.apply(requireCluster(id), deployment));
    }

    @GetMapping("/{id}/operator/deployments/{name}")
    @PreAuthorize("hasAuthority('realtime:flink-cluster:view')")
    public ApiResponse<Map<String, Object>> getDeployment(@PathVariable Long id, @PathVariable String name) {
        return ApiResponse.ok(operatorClient.get(requireCluster(id), name));
    }

    @DeleteMapping("/{id}/operator/deployments/{name}")
    @PreAuthorize("hasAuthority('realtime:flink-cluster:manage')")
    public ApiResponse<Map<String, Object>> deleteDeployment(@PathVariable Long id, @PathVariable String name) {
        return ApiResponse.ok(operatorClient.delete(requireCluster(id), name));
    }

    private FlinkClusterEntity requireCluster(Long id) {
        FlinkClusterEntity cluster = mapper.selectById(id);
        if (cluster == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flink 集群不存在");
        return cluster;
    }

    private void clearPreviousDefault(FlinkClusterEntity cluster, Long currentId) {
        if (!Boolean.TRUE.equals(cluster.getDefaultForEnvironment())) return;
        var wrapper = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FlinkClusterEntity>()
            .eq(FlinkClusterEntity::getEnvironment, cluster.getEnvironment())
            .set(FlinkClusterEntity::getDefaultForEnvironment, false);
        if (currentId != null) wrapper.ne(FlinkClusterEntity::getId, currentId);
        mapper.update(null, wrapper);
    }

    private void validate(FlinkClusterEntity cluster) {
        boolean operator = "KUBERNETES_OPERATOR".equals(cluster.getDeploymentMode());
        if (operator && (cluster.getKubeApiUrl() == null || cluster.getKubeNamespace() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operator 集群必须配置 K8s API 和命名空间");
        }
        if (!operator && (cluster.getRestUrl() == null || cluster.getRestUrl().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Standalone 集群必须配置 Flink REST 地址");
        }
    }
}
