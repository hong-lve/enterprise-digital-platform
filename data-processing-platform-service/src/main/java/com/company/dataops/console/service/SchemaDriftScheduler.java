package com.company.dataops.console.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.entity.SchemaSnapshotEntity;
import com.company.dataops.console.mapper.CdcSourceMapper;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.mapper.SchemaSnapshotMapper;
import com.company.dataops.console.service.kafka.AvroSchemaDiffService;
import com.company.dataops.console.service.kafka.SchemaRegistryClient;
import com.company.dataops.console.service.flink.FlinkStreamSubmissionClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Item 4 of the reliability roadmap's tier 2 ("上下游影响通知"): Schema
 * Registry has no push/webhook mechanism, so this periodically diffs each
 * active CDC source's derived subjects' version list against the last one
 * seen (schema_snapshot) to notice when Debezium has registered a new schema
 * version - i.e. the upstream table actually changed shape. Runs far less
 * often than the 15s health pollers (schema changes are rare, deliberate
 * events, not something needing near-real-time detection).
 *
 * Can't stop a DBA's ALTER TABLE before it happens - by the time a new
 * version shows up here, Debezium already registered it (Schema Registry's
 * own compatibility mode was the actual gate at that moment). What this adds
 * is the thing nothing else in the platform did before: telling the CDC
 * source owner AND every Flink job that declared it consumes the affected
 * topic, with a human-readable field diff, whenever that transition turns
 * out incompatible under whatever mode is currently configured.
 */
@Component
public class SchemaDriftScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaDriftScheduler.class);

    private final CdcSourceMapper cdcSourceMapper;
    private final SchemaSnapshotMapper schemaSnapshotMapper;
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkSqlJobMapper flinkSqlJobMapper;
    private final SchemaRegistryClient schemaRegistryClient;
    private final AvroSchemaDiffService avroSchemaDiffService;
    private final RealtimeAlertService realtimeAlertService;
    private final FlinkStreamSubmissionClient flinkStreamSubmissionClient;
    private final String frontendUrl;

    public SchemaDriftScheduler(
        CdcSourceMapper cdcSourceMapper,
        SchemaSnapshotMapper schemaSnapshotMapper,
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkSqlJobMapper flinkSqlJobMapper,
        SchemaRegistryClient schemaRegistryClient,
        AvroSchemaDiffService avroSchemaDiffService,
        RealtimeAlertService realtimeAlertService,
        FlinkStreamSubmissionClient flinkStreamSubmissionClient,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.cdcSourceMapper = cdcSourceMapper;
        this.schemaSnapshotMapper = schemaSnapshotMapper;
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkSqlJobMapper = flinkSqlJobMapper;
        this.schemaRegistryClient = schemaRegistryClient;
        this.avroSchemaDiffService = avroSchemaDiffService;
        this.realtimeAlertService = realtimeAlertService;
        this.flinkStreamSubmissionClient = flinkStreamSubmissionClient;
        this.frontendUrl = frontendUrl;
    }

    @Scheduled(fixedDelay = 120000, initialDelay = 60000)
    public void checkForDrift() {
        List<CdcSourceEntity> sources = cdcSourceMapper.selectList(new LambdaQueryWrapper<CdcSourceEntity>()
            .isNotNull(CdcSourceEntity::getConnectorName));
        for (CdcSourceEntity source : sources) {
            for (String tableRef : source.getTableIncludeList().split(",")) {
                checkTable(source, tableRef.trim());
            }
        }
    }

    private void checkTable(CdcSourceEntity source, String tableRef) {
        String topic = source.getTopicPrefix() + "." + tableRef;
        String subject = topic + "-value";
        List<Integer> versions;
        try {
            versions = schemaRegistryClient.versions(subject);
        } catch (Exception exception) {
            LOGGER.warn("Failed to check schema versions for subject {}: {}", subject, exception.getMessage());
            return;
        }
        if (versions.isEmpty()) {
            return; // nothing registered yet - no CDC events have flowed for this table
        }
        int latestVersion = versions.stream().mapToInt(Integer::intValue).max().orElseThrow();

        SchemaSnapshotEntity snapshot = schemaSnapshotMapper.selectOne(new LambdaQueryWrapper<SchemaSnapshotEntity>()
            .eq(SchemaSnapshotEntity::getCdcSourceId, source.getId())
            .eq(SchemaSnapshotEntity::getSubject, subject));
        if (snapshot == null) {
            // First time seeing this subject - nothing to compare against yet,
            // just record the current version as the baseline.
            SchemaSnapshotEntity fresh = new SchemaSnapshotEntity();
            fresh.setCdcSourceId(source.getId());
            fresh.setSubject(subject);
            fresh.setLastSeenVersion(latestVersion);
            schemaSnapshotMapper.insert(fresh);
            return;
        }
        if (latestVersion <= snapshot.getLastSeenVersion()) {
            return; // no new version since last poll
        }

        String oldSchema = schemaRegistryClient.schema(subject, snapshot.getLastSeenVersion());
        String newSchema = schemaRegistryClient.schema(subject, latestVersion);
        SchemaRegistryClient.CompatibilityResult compatibility = schemaRegistryClient.checkCompatibility(subject, snapshot.getLastSeenVersion(), newSchema);

        snapshot.setLastSeenVersion(latestVersion);
        snapshot.setLastCompatibilityResult(compatibility.compatible() ? "COMPATIBLE" : "INCOMPATIBLE");
        schemaSnapshotMapper.updateById(snapshot);

        if (compatibility.compatible()) {
            return; // schema evolved, but safely - no one needs to be paged for this
        }
        AvroSchemaDiffService.FieldDiff diff = avroSchemaDiffService.diff(oldSchema, newSchema);
        String detail = describeDiff(diff, compatibility.detail());
        blockAffectedJobs(topic, detail);
        Set<String> owners = downstreamOwners(source, topic);
        realtimeAlertService.notifyMultiple(
            new ArrayList<>(owners),
            "检测到不兼容的 schema 变更：" + topic,
            detail,
            "SCHEMA_DRIFT",
            frontendUrl + "/realtime/cdc-sources"
        );
    }

    private void blockAffectedJobs(String topic, String detail) {
        for (FlinkStreamJobEntity job : flinkStreamJobMapper.selectList(null)) {
            if (!consumesTopic(job.getKafkaTopics(), topic)) {
                continue;
            }
            String savepointPath = job.getSavepointPath();
            String status = job.getStatus();
            if ("RUNNING".equals(status) && job.getFlinkJobId() != null) {
                try {
                    savepointPath = flinkStreamSubmissionClient.stopWithSavepoint(job.getFlinkJobId());
                    status = "CANCELED";
                } catch (Exception exception) {
                    LOGGER.error("Failed to stop schema-blocked stream job {}: {}", job.getId(), exception.getMessage());
                }
            }
            flinkStreamJobMapper.update(null, new LambdaUpdateWrapper<FlinkStreamJobEntity>()
                .eq(FlinkStreamJobEntity::getId, job.getId())
                .set(FlinkStreamJobEntity::getSchemaBlocked, true)
                .set(FlinkStreamJobEntity::getSchemaBlockReason, detail)
                .set(FlinkStreamJobEntity::getStatus, status)
                .set(FlinkStreamJobEntity::getSavepointPath, savepointPath));
        }
        for (FlinkSqlJobEntity job : flinkSqlJobMapper.selectList(null)) {
            if (!consumesTopic(job.getKafkaTopics(), topic)) {
                continue;
            }
            String savepointPath = job.getSavepointPath();
            String status = job.getStatus();
            if ("RUNNING".equals(status) && job.getFlinkJobId() != null) {
                try {
                    savepointPath = flinkStreamSubmissionClient.stopWithSavepoint(job.getFlinkJobId());
                    status = "CANCELED";
                } catch (Exception exception) {
                    LOGGER.error("Failed to stop schema-blocked SQL job {}: {}", job.getId(), exception.getMessage());
                }
            }
            flinkSqlJobMapper.update(null, new LambdaUpdateWrapper<FlinkSqlJobEntity>()
                .eq(FlinkSqlJobEntity::getId, job.getId())
                .set(FlinkSqlJobEntity::getSchemaBlocked, true)
                .set(FlinkSqlJobEntity::getSchemaBlockReason, detail)
                .set(FlinkSqlJobEntity::getStatus, status)
                .set(FlinkSqlJobEntity::getSavepointPath, savepointPath));
        }
    }

    private String describeDiff(AvroSchemaDiffService.FieldDiff diff, String compatibilityDetail) {
        StringBuilder message = new StringBuilder();
        if (!diff.removedFields().isEmpty()) {
            message.append("删除字段：").append(String.join(", ", diff.removedFields())).append("；");
        }
        if (!diff.typeChanges().isEmpty()) {
            diff.typeChanges().forEach(change ->
                message.append(change.field()).append(" 类型从 ").append(change.oldType()).append(" 变为 ").append(change.newType()).append("；"));
        }
        if (!diff.addedFields().isEmpty()) {
            message.append("新增字段：").append(String.join(", ", diff.addedFields())).append("；");
        }
        if (compatibilityDetail != null && !compatibilityDetail.isBlank()) {
            message.append("Schema Registry：").append(compatibilityDetail);
        }
        return message.length() == 0 ? "Schema Registry 判定不兼容，但未能解析出具体字段差异" : message.toString();
    }

    /** CDC source's own owner, plus every Flink stream/SQL job that declared it consumes this exact topic. */
    private Set<String> downstreamOwners(CdcSourceEntity source, String topic) {
        Set<String> owners = new LinkedHashSet<>();
        if (source.getOwner() != null && !source.getOwner().isBlank()) {
            owners.add(source.getOwner());
        }
        for (FlinkStreamJobEntity job : flinkStreamJobMapper.selectList(null)) {
            if (consumesTopic(job.getKafkaTopics(), topic) && job.getOwner() != null && !job.getOwner().isBlank()) {
                owners.add(job.getOwner());
            }
        }
        for (FlinkSqlJobEntity job : flinkSqlJobMapper.selectList(null)) {
            if (consumesTopic(job.getKafkaTopics(), topic) && job.getOwner() != null && !job.getOwner().isBlank()) {
                owners.add(job.getOwner());
            }
        }
        return owners;
    }

    private boolean consumesTopic(String kafkaTopics, String topic) {
        if (kafkaTopics == null || kafkaTopics.isBlank()) {
            return false;
        }
        return Arrays.stream(kafkaTopics.split(",")).map(String::trim).anyMatch(topic::equals);
    }
}
