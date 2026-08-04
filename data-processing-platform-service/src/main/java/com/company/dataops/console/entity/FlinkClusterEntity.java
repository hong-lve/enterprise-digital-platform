package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("flink_cluster")
public class FlinkClusterEntity {
    private Long id;
    @NotBlank(message = "集群名称不能为空")
    private String name;
    @NotBlank(message = "环境不能为空")
    private String environment;
    @NotBlank(message = "部署模式不能为空")
    private String deploymentMode;
    private String restUrl;
    private String sqlGatewayUrl;
    private String kubeApiUrl;
    private String kubeNamespace;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String kubeTokenEnv;
    private String flinkImage;
    private String serviceAccount;
    private Boolean defaultForEnvironment;
    private Boolean enabled;
    private String owner;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
