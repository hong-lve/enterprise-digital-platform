package com.company.dataops.console.service.versioning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.dataops.console.entity.JobVersionSnapshotEntity;
import com.company.dataops.console.mapper.JobVersionSnapshotMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tier 3 item 2 of the reliability roadmap ("作业版本快照、配置差异与快速回滚") -
 * an immutable, append-only history of every definition change a Flink
 * stream job or SQL job goes through, keyed generically by (entityType,
 * entityId) so both job kinds share one implementation, same pattern as
 * RecoveryOrchestrator. Only records config *changes* (create/edit/rolling
 * upgrade/rollback) - starting or stopping an already-defined job doesn't
 * produce a new version, since nothing about its definition changed.
 *
 * By explicit user choice this stays deliberately simple: no automatic
 * canary/health-check rollback, just version history + field-level diff +
 * one-click manual rollback - see [[project_reliability_hardening_roadmap]].
 */
@Component
public class JobVersionSnapshotService {
    private final JobVersionSnapshotMapper jobVersionSnapshotMapper;
    private final ObjectMapper objectMapper;

    public JobVersionSnapshotService(JobVersionSnapshotMapper jobVersionSnapshotMapper, ObjectMapper objectMapper) {
        this.jobVersionSnapshotMapper = jobVersionSnapshotMapper;
        this.objectMapper = objectMapper;
    }

    /** configSnapshot should already have runtime-only fields (status/flinkJobId/lastError/...) nulled out by the caller. */
    public int recordVersion(String entityType, Long entityId, Object configSnapshot, String savepointPath, String flinkJobId, String changeSummary, Integer rollbackOfVersion) {
        int versionNo = nextVersionNo(entityType, entityId);
        JobVersionSnapshotEntity entity = new JobVersionSnapshotEntity();
        entity.setEntityType(entityType);
        entity.setEntityId(entityId);
        entity.setVersionNo(versionNo);
        entity.setConfigJson(writeJson(configSnapshot));
        entity.setSavepointPath(savepointPath);
        entity.setFlinkJobId(flinkJobId);
        entity.setChangeSummary(changeSummary);
        entity.setRollbackOfVersion(rollbackOfVersion);
        entity.setCreatedBy(currentUsername());
        entity.setCreatedAt(LocalDateTime.now());
        jobVersionSnapshotMapper.insert(entity);
        return versionNo;
    }

    public List<JobVersionSnapshotEntity> history(String entityType, Long entityId) {
        return jobVersionSnapshotMapper.selectList(new LambdaQueryWrapper<JobVersionSnapshotEntity>()
            .eq(JobVersionSnapshotEntity::getEntityType, entityType)
            .eq(JobVersionSnapshotEntity::getEntityId, entityId)
            .orderByDesc(JobVersionSnapshotEntity::getVersionNo));
    }

    public JobVersionSnapshotEntity requireVersion(String entityType, Long entityId, int versionNo) {
        JobVersionSnapshotEntity snapshot = jobVersionSnapshotMapper.selectOne(new LambdaQueryWrapper<JobVersionSnapshotEntity>()
            .eq(JobVersionSnapshotEntity::getEntityType, entityType)
            .eq(JobVersionSnapshotEntity::getEntityId, entityId)
            .eq(JobVersionSnapshotEntity::getVersionNo, versionNo));
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本 " + versionNo + " 不存在");
        }
        return snapshot;
    }

    public <T> T readConfig(JobVersionSnapshotEntity snapshot, Class<T> type) {
        try {
            return objectMapper.readValue(snapshot.getConfigJson(), type);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析版本 " + snapshot.getVersionNo() + " 的配置失败：" + exception.getMessage());
        }
    }

    /** Field-by-field diff of the two versions' config_json - generic, doesn't care which entity type. */
    public List<FieldChange> diff(String entityType, Long entityId, int fromVersion, int toVersion) {
        JobVersionSnapshotEntity from = requireVersion(entityType, entityId, fromVersion);
        JobVersionSnapshotEntity to = requireVersion(entityType, entityId, toVersion);
        JsonNode fromNode = readTree(from.getConfigJson());
        JsonNode toNode = readTree(to.getConfigJson());
        TreeSet<String> fields = new TreeSet<>();
        fromNode.fieldNames().forEachRemaining(fields::add);
        toNode.fieldNames().forEachRemaining(fields::add);
        List<FieldChange> changes = new ArrayList<>();
        for (String field : fields) {
            String oldValue = textValue(fromNode.get(field));
            String newValue = textValue(toNode.get(field));
            if (!Objects.equals(oldValue, newValue)) {
                changes.add(new FieldChange(field, oldValue, newValue));
            }
        }
        return changes;
    }

    private int nextVersionNo(String entityType, Long entityId) {
        List<JobVersionSnapshotEntity> existing = history(entityType, entityId);
        return existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1;
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "解析版本配置失败：" + exception.getMessage());
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化版本配置失败：" + exception.getMessage());
        }
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    public record FieldChange(String field, String oldValue, String newValue) {
    }
}
