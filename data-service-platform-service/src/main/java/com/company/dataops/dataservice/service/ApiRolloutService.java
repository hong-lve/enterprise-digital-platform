package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.ApiRolloutDetail;
import com.company.dataops.dataservice.domain.ApiRolloutRecord;
import com.company.dataops.dataservice.domain.ApiVersionRecord;
import com.company.dataops.dataservice.domain.DataApiRecord;
import com.company.dataops.dataservice.domain.RolloutHealthPolicy;
import com.company.dataops.dataservice.domain.RolloutHealthSnapshot;
import com.company.dataops.dataservice.domain.RolloutStage;
import com.company.dataops.dataservice.repository.ApiRolloutRepository;
import com.company.dataops.dataservice.repository.ApplicationRepository;
import com.company.dataops.dataservice.repository.DataApiRepository;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiRolloutService {
    private final ApiRolloutRepository rolloutRepository;
    private final DataApiRepository apiRepository;
    private final ApplicationRepository applicationRepository;
    private final ApiLifecyclePolicy lifecyclePolicy;
    private final ApiReleaseGateService releaseGateService;
    private final NotificationService notificationService;

    public ApiRolloutService(
        ApiRolloutRepository rolloutRepository,
        DataApiRepository apiRepository,
        ApplicationRepository applicationRepository,
        ApiLifecyclePolicy lifecyclePolicy,
        ApiReleaseGateService releaseGateService,
        NotificationService notificationService
    ) {
        this.rolloutRepository = rolloutRepository;
        this.apiRepository = apiRepository;
        this.applicationRepository = applicationRepository;
        this.lifecyclePolicy = lifecyclePolicy;
        this.releaseGateService = releaseGateService;
        this.notificationService = notificationService;
    }

    public ApiRolloutDetail detail(long apiId) {
        List<ApiRolloutRecord> rollouts = rolloutRepository.findByApiId(apiId);
        if (rollouts.isEmpty()) {
            return new ApiRolloutDetail(List.of(), List.of(), null, List.of());
        }
        ApiRolloutRecord latest = rollouts.get(0);
        List<com.company.dataops.dataservice.domain.RolloutVariantMetrics> metrics =
            rolloutRepository.metrics(latest.id());
        RolloutHealthSnapshot health = latest.stageStartedAt() == null
            ? null
            : rolloutRepository.health(latest.id(), latest.stageStartedAt());
        return new ApiRolloutDetail(
            rollouts,
            metrics,
            health,
            rolloutRepository.events(latest.id())
        );
    }

    @Transactional
    public ApiRolloutRecord start(
        long apiId,
        int candidateVersionNo,
        int percentage,
        Set<Long> applicationIds,
        List<String> ipRules,
        String note,
        String actor,
        List<RolloutStage> stages,
        RolloutHealthPolicy healthPolicy,
        String failureAction
    ) {
        boolean automated = stages != null && !stages.isEmpty();
        String safeFailureAction = failureAction(failureAction);
        if (automated) {
            validateAutomation(stages, healthPolicy);
            percentage = stages.get(0).percentage();
        } else {
            validateAllocation(percentage, applicationIds, ipRules);
        }
        DataApiRecord api = apiRepository.findPublishedById(apiId)
            .orElseThrow(() -> notFound("Published API not found"));
        ApiVersionRecord candidate = apiRepository.findVersion(apiId, candidateVersionNo)
            .orElseThrow(() -> notFound("Candidate API version not found"));
        lifecyclePolicy.assertReviewable(candidate.status(), candidate.submittedBy(), actor);
        assertNoActive(apiId);
        validateApplications(apiId, applicationIds);
        validateIpRules(ipRules);
        releaseGateService.verify(api, candidate, actor);
        ApiRolloutRecord rollout;
        try {
            rollout = rolloutRepository.create(
                apiId,
                api.publishedVersion(),
                candidateVersionNo,
                percentage,
                safeApplications(applicationIds),
                safeIpRules(ipRules),
                trim(note),
                actor,
                automated,
                automated ? List.copyOf(stages) : List.of(),
                automated ? healthPolicy : null,
                safeFailureAction,
                automated
                    ? Instant.now().plusSeconds(stages.get(0).observationMinutes() * 60L)
                    : null
            );
        } catch (DuplicateKeyException exception) {
            throw conflict("An active canary rollout already exists for this API");
        }
        apiRepository.markCanary(apiId, candidateVersionNo, actor, note);
        rolloutRepository.saveEvent(
            rollout.id(),
            "ROLLOUT_STARTED",
            0,
            percentage,
            automated ? "Automated canary rollout started" : "Manual canary rollout started",
            actor,
            automated ? Map.of("stages", stages.size(), "failureAction", safeFailureAction) : Map.of()
        );
        return rollout;
    }

    @Transactional
    public ApiRolloutRecord update(
        long rolloutId,
        int percentage,
        Set<Long> applicationIds,
        List<String> ipRules,
        String note,
        String actor
    ) {
        validateAllocation(percentage, applicationIds, ipRules);
        ApiRolloutRecord rollout = requireActive(rolloutId);
        if ("PAUSED".equals(rollout.status())) {
            throw conflict("Resume the rollout before changing traffic rules");
        }
        validateApplications(rollout.apiId(), applicationIds);
        validateIpRules(ipRules);
        try {
            ApiRolloutRecord updated = rolloutRepository.update(
                rolloutId,
                percentage,
                safeApplications(applicationIds),
                safeIpRules(ipRules),
                trim(note),
                actor
            );
            rolloutRepository.saveEvent(
                rolloutId, "TRAFFIC_UPDATED", null, percentage,
                "Traffic allocation updated manually; automation disabled", actor, Map.of()
            );
            return updated;
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
    }

    @Transactional
    public DataApiRecord promote(long rolloutId, String actor) {
        ApiRolloutRecord rollout = requireActive(rolloutId);
        DataApiRecord api = apiRepository.promoteCanary(
            rollout.apiId(), rollout.candidateVersionNo(), actor, rollout.note()
        );
        rolloutRepository.finish(rolloutId, "PROMOTED", actor);
        rolloutRepository.saveEvent(
            rolloutId, "ROLLOUT_PROMOTED", rollout.currentStageIndex(), 100,
            "Candidate version promoted to production", actor, Map.of()
        );
        notifyRollout(rollout, "CANARY_PROMOTED", "Canary rollout promoted",
            "API " + rollout.apiId() + " candidate v" + rollout.candidateVersionNo()
                + " is now fully published.");
        return api;
    }

    @Transactional
    public ApiRolloutRecord rollback(long rolloutId, String actor) {
        ApiRolloutRecord rollout = requireActive(rolloutId);
        apiRepository.archiveCanary(rollout.apiId(), rollout.candidateVersionNo(), actor);
        rolloutRepository.finish(rolloutId, "ROLLED_BACK", actor);
        rolloutRepository.saveEvent(
            rolloutId, "ROLLOUT_ROLLED_BACK", rollout.currentStageIndex(), rollout.percentage(),
            "Canary traffic returned to the stable version", actor, Map.of()
        );
        notifyRollout(rollout, "CANARY_ROLLED_BACK", "Canary rollout rolled back",
            "API " + rollout.apiId() + " returned to stable v" + rollout.baselineVersionNo() + ".");
        return rolloutRepository.findById(rolloutId).orElseThrow();
    }

    @Transactional
    public ApiRolloutRecord pause(long rolloutId, String reason, String actor) {
        ApiRolloutRecord rollout = requireActive(rolloutId);
        if ("PAUSED".equals(rollout.status())) {
            throw conflict("Canary rollout is already paused");
        }
        String safeReason = trim(reason);
        if (safeReason == null) {
            safeReason = "Paused manually";
        }
        try {
            ApiRolloutRecord paused = rolloutRepository.pause(rolloutId, safeReason, actor);
            rolloutRepository.saveEvent(
                rolloutId, "ROLLOUT_PAUSED", rollout.currentStageIndex(), rollout.percentage(),
                safeReason, actor, Map.of()
            );
            notifyRollout(rollout, "CANARY_PAUSED", "Canary rollout paused", safeReason);
            return paused;
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
    }

    @Transactional
    public ApiRolloutRecord resume(long rolloutId, String actor) {
        ApiRolloutRecord rollout = requireActive(rolloutId);
        if (!"PAUSED".equals(rollout.status()) || !rollout.automated()) {
            throw conflict("Only a paused automated rollout can be resumed");
        }
        RolloutStage stage = rollout.stages().get(rollout.currentStageIndex());
        ApiRolloutRecord resumed = rolloutRepository.resume(
            rolloutId,
            Instant.now().plusSeconds(stage.observationMinutes() * 60L),
            actor
        );
        rolloutRepository.saveEvent(
            rolloutId, "ROLLOUT_RESUMED", rollout.currentStageIndex(), rollout.percentage(),
            "Observation window restarted", actor, Map.of()
        );
        return resumed;
    }

    @Transactional
    public void evaluateAutomated(long rolloutId, String worker) {
        if (!rolloutRepository.ownsLock(rolloutId, worker)) {
            return;
        }
        ApiRolloutRecord rollout = rolloutRepository.findById(rolloutId).orElse(null);
        if (rollout == null || !"ACTIVE".equals(rollout.status()) || !rollout.automated()) {
            rolloutRepository.releaseLock(rolloutId, worker);
            return;
        }
        RolloutHealthSnapshot health = rolloutRepository.health(
            rollout.id(), rollout.stageStartedAt()
        );
        String failure = healthFailure(rollout.healthPolicy(), health);
        if (failure != null) {
            handleHealthFailure(rollout, health, failure);
            return;
        }

        int nextIndex = rollout.currentStageIndex() + 1;
        if (nextIndex >= rollout.stages().size()
            || rollout.stages().get(nextIndex).percentage() >= 100) {
            apiRepository.promoteCanary(
                rollout.apiId(), rollout.candidateVersionNo(), "canary-scheduler", rollout.note()
            );
            rolloutRepository.finish(rollout.id(), "PROMOTED", "canary-scheduler");
            rolloutRepository.saveEvent(
                rollout.id(), "AUTO_PROMOTED", rollout.currentStageIndex(), 100,
                "Health gate passed; candidate automatically promoted",
                "canary-scheduler", healthDetails(health)
            );
            notifyRollout(rollout, "CANARY_AUTO_PROMOTED", "Canary rollout completed",
                "All health gates passed and API " + rollout.apiId() + " was promoted.");
            return;
        }

        RolloutStage next = rollout.stages().get(nextIndex);
        rolloutRepository.advanceStage(
            rollout.id(),
            nextIndex,
            next.percentage(),
            Instant.now().plusSeconds(next.observationMinutes() * 60L),
            "canary-scheduler"
        );
        rolloutRepository.saveEvent(
            rollout.id(), "STAGE_ADVANCED", nextIndex, next.percentage(),
            "Health gate passed; traffic advanced to " + next.percentage() + "%",
            "canary-scheduler", healthDetails(health)
        );
        notifyRollout(rollout, "CANARY_STAGE_ADVANCED", "Canary rollout advanced",
            "API " + rollout.apiId() + " advanced to " + next.percentage() + "% canary traffic.");
    }

    public void assertNoActive(long apiId) {
        if (rolloutRepository.findActive(apiId).isPresent()) {
            throw conflict("An active canary rollout already exists for this API");
        }
    }

    public RouteDecision route(DataApiRecord baseline, long appId, String appKey, String clientIp) {
        ApiRolloutRecord rollout = rolloutRepository.findActive(baseline.id()).orElse(null);
        if (rollout == null) {
            return new RouteDecision(baseline, null, "STABLE");
        }
        boolean targeted = rollout.applicationIds().contains(appId)
            || rollout.ipRules().stream().anyMatch(rule -> matchesIp(clientIp, rule));
        boolean percentageMatch = bucket(baseline.id(), appId, appKey, clientIp) < rollout.percentage();
        if (!targeted && !percentageMatch) {
            return new RouteDecision(baseline, rollout.id(), "STABLE");
        }
        return apiRepository.findVersion(baseline.id(), rollout.candidateVersionNo())
            .map(version -> new RouteDecision(version.asApi(baseline), rollout.id(), "CANARY"))
            .orElseGet(() -> new RouteDecision(baseline, rollout.id(), "STABLE"));
    }

    private ApiRolloutRecord requireActive(long rolloutId) {
        ApiRolloutRecord rollout = rolloutRepository.findById(rolloutId)
            .orElseThrow(() -> notFound("Canary rollout not found"));
        if (!Set.of("ACTIVE", "PAUSED").contains(rollout.status())) {
            throw conflict("Canary rollout is no longer active");
        }
        return rollout;
    }

    private void validateAllocation(
        int percentage,
        Set<Long> applicationIds,
        List<String> ipRules
    ) {
        if (percentage < 0 || percentage > 99) {
            throw badRequest("Canary percentage must be between 0 and 99");
        }
        if (percentage == 0
            && (applicationIds == null || applicationIds.isEmpty())
            && (ipRules == null || ipRules.isEmpty())) {
            throw badRequest("Configure a percentage, application, or IP rule");
        }
    }

    private void validateAutomation(
        List<RolloutStage> stages,
        RolloutHealthPolicy policy
    ) {
        if (stages.size() < 2 || stages.size() > 10) {
            throw badRequest("Automated rollout requires 2 to 10 stages");
        }
        int previous = 0;
        for (int index = 0; index < stages.size(); index++) {
            RolloutStage stage = stages.get(index);
            if (stage == null || stage.percentage() == null || stage.observationMinutes() == null) {
                throw badRequest("Every rollout stage requires percentage and observation time");
            }
            if (stage.percentage() <= previous || stage.percentage() > 100) {
                throw badRequest("Rollout stage percentages must increase and end at 100");
            }
            boolean finalStage = index == stages.size() - 1;
            if ((!finalStage && (stage.observationMinutes() < 1 || stage.observationMinutes() > 1440))
                || (finalStage && stage.observationMinutes() < 0)) {
                throw badRequest("Stage observation time must be between 1 and 1440 minutes");
            }
            previous = stage.percentage();
        }
        if (previous != 100) {
            throw badRequest("The final automated rollout stage must be 100%");
        }
        if (policy == null
            || policy.minimumRequests() == null || policy.minimumRequests() < 1
            || policy.minimumSuccessRate() == null
            || policy.minimumSuccessRate() < 0 || policy.minimumSuccessRate() > 100
            || policy.maximumErrorRate() == null
            || policy.maximumErrorRate() < 0 || policy.maximumErrorRate() > 100
            || policy.maximumP95Ms() == null || policy.maximumP95Ms() < 1
            || policy.maximumP99Ms() == null
            || policy.maximumP99Ms() < policy.maximumP95Ms()) {
            throw badRequest("Invalid automated rollout health policy");
        }
    }

    private String failureAction(String value) {
        String action = value == null ? "PAUSE" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("PAUSE", "ROLLBACK").contains(action)) {
            throw badRequest("Failure action must be PAUSE or ROLLBACK");
        }
        return action;
    }

    private String healthFailure(RolloutHealthPolicy policy, RolloutHealthSnapshot health) {
        if (health.requestCount() < policy.minimumRequests()) {
            return "Insufficient sample size: " + health.requestCount() + "/"
                + policy.minimumRequests();
        }
        if (health.successRate() < policy.minimumSuccessRate()) {
            return "Success rate " + health.successRate() + "% is below "
                + policy.minimumSuccessRate() + "%";
        }
        if (health.errorRate() > policy.maximumErrorRate()) {
            return "Error rate " + health.errorRate() + "% exceeds "
                + policy.maximumErrorRate() + "%";
        }
        if (health.p95ElapsedMs() > policy.maximumP95Ms()) {
            return "P95 latency " + health.p95ElapsedMs() + " ms exceeds "
                + policy.maximumP95Ms() + " ms";
        }
        if (health.p99ElapsedMs() > policy.maximumP99Ms()) {
            return "P99 latency " + health.p99ElapsedMs() + " ms exceeds "
                + policy.maximumP99Ms() + " ms";
        }
        return null;
    }

    private void handleHealthFailure(
        ApiRolloutRecord rollout,
        RolloutHealthSnapshot health,
        String failure
    ) {
        boolean insufficientSamples = health.requestCount() < rollout.healthPolicy().minimumRequests();
        boolean rollback = !insufficientSamples && "ROLLBACK".equals(rollout.failureAction());
        if (rollback) {
            apiRepository.archiveCanary(
                rollout.apiId(), rollout.candidateVersionNo(), "canary-scheduler"
            );
            rolloutRepository.finish(rollout.id(), "ROLLED_BACK", "canary-scheduler");
            rolloutRepository.saveEvent(
                rollout.id(), "AUTO_ROLLED_BACK", rollout.currentStageIndex(), rollout.percentage(),
                failure, "canary-scheduler", healthDetails(health)
            );
            notifyRollout(rollout, "CANARY_AUTO_ROLLED_BACK", "Canary automatically rolled back", failure);
            return;
        }
        rolloutRepository.pause(rollout.id(), failure, "canary-scheduler");
        rolloutRepository.saveEvent(
            rollout.id(), "AUTO_PAUSED", rollout.currentStageIndex(), rollout.percentage(),
            failure, "canary-scheduler", healthDetails(health)
        );
        notifyRollout(rollout, "CANARY_AUTO_PAUSED", "Canary automatically paused", failure);
    }

    private Map<String, Object> healthDetails(RolloutHealthSnapshot health) {
        return Map.of(
            "requestCount", health.requestCount(),
            "successRate", health.successRate(),
            "errorRate", health.errorRate(),
            "averageElapsedMs", health.averageElapsedMs(),
            "p95ElapsedMs", health.p95ElapsedMs(),
            "p99ElapsedMs", health.p99ElapsedMs()
        );
    }

    private void notifyRollout(
        ApiRolloutRecord rollout,
        String eventType,
        String title,
        String content
    ) {
        notificationService.enqueueEvent(eventType, title, content, Map.of(
            "rolloutId", rollout.id(),
            "apiId", rollout.apiId(),
            "baselineVersion", rollout.baselineVersionNo(),
            "candidateVersion", rollout.candidateVersionNo()
        ));
    }

    private void validateApplications(long apiId, Set<Long> applicationIds) {
        if (applicationIds == null || applicationIds.isEmpty()) {
            return;
        }
        Set<Long> known = applicationRepository.findAll().stream()
            .filter(application -> application.authorizedApiIds().contains(apiId))
            .map(application -> application.id())
            .collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(applicationIds)) {
            throw badRequest("One or more rollout applications do not exist");
        }
    }

    private void validateIpRules(List<String> ipRules) {
        safeIpRules(ipRules).forEach(rule -> {
            try {
                IpRange.parse(rule);
            } catch (IllegalArgumentException exception) {
                throw badRequest("Invalid IP or CIDR rule: " + rule);
            }
        });
    }

    private boolean matchesIp(String clientIp, String rule) {
        try {
            return IpRange.parse(rule).contains(InetAddress.getByName(clientIp));
        } catch (IllegalArgumentException | UnknownHostException exception) {
            return false;
        }
    }

    private int bucket(long apiId, long appId, String appKey, String clientIp) {
        String identity = appId > 0 ? "app:" + appId : "key:" + appKey + ":ip:" + clientIp;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((apiId + ":" + identity).getBytes(StandardCharsets.UTF_8));
            return Math.floorMod(ByteBuffer.wrap(digest).getInt(), 100);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Set<Long> safeApplications(Set<Long> values) {
        return values == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(values));
    }

    private List<String> safeIpRules(List<String> values) {
        return values == null ? List.of() : values.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record RouteDecision(DataApiRecord api, Long rolloutId, String variant) {
    }

    private record IpRange(byte[] network, int prefixLength) {
        static IpRange parse(String rule) {
            try {
                String[] parts = rule.trim().split("/", -1);
                if (parts[0].isBlank() || !parts[0].matches("[0-9A-Fa-f:.]+")) {
                    throw new IllegalArgumentException("IP address must be numeric");
                }
                InetAddress address = InetAddress.getByName(parts[0]);
                int bits = address.getAddress().length * 8;
                int prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
                if (parts.length > 2 || prefix < 0 || prefix > bits) {
                    throw new IllegalArgumentException("Invalid CIDR prefix");
                }
                return new IpRange(address.getAddress(), prefix);
            } catch (UnknownHostException | NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid IP range", exception);
            }
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainder = prefixLength % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (candidate[index] != network[index]) {
                    return false;
                }
            }
            if (remainder == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainder);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
