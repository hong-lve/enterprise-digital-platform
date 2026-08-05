package com.company.dataops.console.api;

import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.entity.FlinkSqlJobEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.CdcSourceMapper;
import com.company.dataops.console.mapper.FlinkSqlJobMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.lineage.FlinkSqlLineageParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Real-time-only lineage: MySQL table (CDC source) -> Kafka topic -> Flink
 * job -> ClickHouse table. Deliberately doesn't reach into
 * data-processing-platform-service (offline side) - this repo keeps those two
 * platforms separate, see PROJECT_SPLIT.md.
 *
 * A CDC source's topics are computed from its own structured fields
 * (topicPrefix + table from tableIncludeList), not stored anywhere new. A
 * jar job's declared kafkaTopics/clickhouseSinkTables are optional fields
 * the job's submitter fills in (see FlinkStreamJobEntity) - the platform
 * can't infer either from the jar itself.
 *
 * SQL jobs (FlinkSqlJobEntity) show up as consumers the same way jar jobs
 * do, distinguished by jobType on FlinkJobLineage. A SQL job can declare
 * clickhouseSinkTables and/or dorisSinkTables and/or oracleSinkTables (same
 * optional/comma-separated/user-declared fields jar
 * jobs have for ClickHouse - the platform doesn't parse the SQL script to infer either,
 * same reasoning as kafka_consumer_group_id); all get merged into one
 * sinkTables list since the lineage graph doesn't care which engine a table
 * lives in. A SQL job that only writes to another Kafka topic (still the
 * common case - not every job writes to an OLAP sink) leaves all three
 * blank, and the frontend's leaf-node text for an empty sinkTables list is
 * worded differently for SQL jobs than jar jobs to make that distinction
 * honest instead of implying someone forgot to fill in a field that
 * doesn't apply to them.
 */
@RestController
@RequestMapping("/realtime/lineage")
public class LineageController {
    private final CdcSourceMapper cdcSourceMapper;
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final FlinkSqlJobMapper flinkSqlJobMapper;
    private final FlinkSqlLineageParser flinkSqlLineageParser;

    public LineageController(
        CdcSourceMapper cdcSourceMapper,
        FlinkStreamJobMapper flinkStreamJobMapper,
        FlinkSqlJobMapper flinkSqlJobMapper,
        FlinkSqlLineageParser flinkSqlLineageParser
    ) {
        this.cdcSourceMapper = cdcSourceMapper;
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.flinkSqlJobMapper = flinkSqlJobMapper;
        this.flinkSqlLineageParser = flinkSqlLineageParser;
    }

    /**
     * Column-level lineage (tier 2 item 3 of the reliability roadmap) - parses
     * the job's own sqlScript on demand rather than the hand-filled
     * kafkaTopics/*SinkTables fields the rest of this controller relies on.
     * No new table/migration: re-parsing on each request is cheap (the
     * script is already stored), so there's nothing worth caching.
     */
    @GetMapping("/sql-jobs/{id}/columns")
    @PreAuthorize("hasAuthority('realtime:lineage:view')")
    public ApiResponse<FlinkSqlLineageParser.SqlLineageResult> sqlJobColumnLineage(@PathVariable Long id) {
        FlinkSqlJobEntity job = flinkSqlJobMapper.selectById(id);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SQL 流作业不存在");
        }
        return ApiResponse.ok(flinkSqlLineageParser.parse(job.getSqlScript()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:lineage:view')")
    public ApiResponse<LineageView> lineage() {
        List<CdcSourceEntity> cdcSources = cdcSourceMapper.selectList(null);
        List<FlinkStreamJobEntity> jarJobs = flinkStreamJobMapper.selectList(null);
        List<FlinkSqlJobEntity> sqlJobs = flinkSqlJobMapper.selectList(null);

        Set<String> topicsClaimedByCdcSources = cdcSources.stream()
            .flatMap(source -> topicsProducedBy(source).stream())
            .collect(java.util.stream.Collectors.toSet());

        List<CdcSourceLineage> cdcSourceLineages = cdcSources.stream()
            .map(source -> {
                List<TopicLineage> topics = topicsProducedBy(source).stream()
                    .map(topic -> new TopicLineage(topic, consumersOf(jarJobs, sqlJobs, topic)))
                    .toList();
                return new CdcSourceLineage(source.getId(), source.getName(), source.getStatus(), topics);
            })
            .toList();

        // Jobs that declare a topic but it doesn't match any known CDC
        // source's output - not an error, just worth surfacing (e.g. the
        // CDC source that used to produce it was deleted, or the topic was
        // created some other way entirely).
        List<FlinkJobLineage> orphanFlinkJobs = new ArrayList<>();
        jarJobs.stream()
            .filter(job -> hasDeclaredTopics(job) && splitTopics(job).stream().noneMatch(topicsClaimedByCdcSources::contains))
            .map(this::toFlinkJobLineage)
            .forEach(orphanFlinkJobs::add);
        sqlJobs.stream()
            .filter(job -> hasDeclaredTopics(job) && splitTopics(job).stream().noneMatch(topicsClaimedByCdcSources::contains))
            .map(this::toFlinkJobLineage)
            .forEach(orphanFlinkJobs::add);

        return ApiResponse.ok(new LineageView(cdcSourceLineages, orphanFlinkJobs));
    }

    private List<String> topicsProducedBy(CdcSourceEntity source) {
        if (source.getTableIncludeList() == null || source.getTableIncludeList().isBlank() || source.getTopicPrefix() == null) {
            return List.of();
        }
        List<String> topics = new ArrayList<>();
        for (String table : source.getTableIncludeList().split(",")) {
            topics.add(source.getTopicPrefix() + "." + table.trim());
        }
        return topics;
    }

    private List<FlinkJobLineage> consumersOf(List<FlinkStreamJobEntity> jarJobs, List<FlinkSqlJobEntity> sqlJobs, String topic) {
        List<FlinkJobLineage> consumers = new ArrayList<>();
        jarJobs.stream()
            .filter(job -> hasDeclaredTopics(job) && splitTopics(job).contains(topic))
            .map(this::toFlinkJobLineage)
            .forEach(consumers::add);
        sqlJobs.stream()
            .filter(job -> hasDeclaredTopics(job) && splitTopics(job).contains(topic))
            .map(this::toFlinkJobLineage)
            .forEach(consumers::add);
        return consumers;
    }

    private boolean hasDeclaredTopics(FlinkStreamJobEntity job) {
        return job.getKafkaTopics() != null && !job.getKafkaTopics().isBlank();
    }

    private boolean hasDeclaredTopics(FlinkSqlJobEntity job) {
        return job.getKafkaTopics() != null && !job.getKafkaTopics().isBlank();
    }

    private List<String> splitTopics(FlinkStreamJobEntity job) {
        List<String> topics = new ArrayList<>();
        for (String topic : job.getKafkaTopics().split(",")) {
            topics.add(topic.trim());
        }
        return topics;
    }

    private List<String> splitTopics(FlinkSqlJobEntity job) {
        List<String> topics = new ArrayList<>();
        for (String topic : job.getKafkaTopics().split(",")) {
            topics.add(topic.trim());
        }
        return topics;
    }

    private FlinkJobLineage toFlinkJobLineage(FlinkStreamJobEntity job) {
        return new FlinkJobLineage(job.getId(), job.getName(), job.getStatus(), "JAR", parseSinkTables(job.getClickhouseSinkTables(), "CLICKHOUSE"));
    }

    private FlinkJobLineage toFlinkJobLineage(FlinkSqlJobEntity job) {
        List<SinkTableLineage> sinkTables = new ArrayList<>(parseSinkTables(job.getClickhouseSinkTables(), "CLICKHOUSE"));
        sinkTables.addAll(parseSinkTables(job.getDorisSinkTables(), "DORIS"));
        sinkTables.addAll(parseSinkTables(job.getOracleSinkTables(), "ORACLE"));
        return new FlinkJobLineage(job.getId(), job.getName(), job.getStatus(), "SQL", sinkTables);
    }

    private List<SinkTableLineage> parseSinkTables(String commaSeparated, String sinkType) {
        return commaSeparated == null || commaSeparated.isBlank()
            ? List.of()
            : List.of(commaSeparated.split(",")).stream().map(table -> new SinkTableLineage(table.trim(), sinkType)).toList();
    }

    public record LineageView(List<CdcSourceLineage> cdcSources, List<FlinkJobLineage> orphanFlinkJobs) {
    }

    public record CdcSourceLineage(Long id, String name, String status, List<TopicLineage> topics) {
    }

    public record TopicLineage(String topic, List<FlinkJobLineage> consumers) {
    }

    public record FlinkJobLineage(Long id, String name, String status, String jobType, List<SinkTableLineage> sinkTables) {
    }

    // sinkType: "CLICKHOUSE" | "DORIS" | "ORACLE" - previously sinkTables was a flat
    // List<String> merging both, which lost which engine each table
    // actually lives in and made the frontend mislabel every entry as
    // ClickHouse (a SQL job's Doris sink table rendered with a "ClickHouse
    // 表" tag).
    public record SinkTableLineage(String table, String sinkType) {
    }
}
