package com.company.dataops.console.service.approval;

import com.company.dataops.console.entity.ChangeRequestEntity;
import com.company.dataops.console.mapper.ChangeRequestMapper;
import com.company.dataops.console.security.LocalAuthorityService;
import com.company.dataops.console.service.RealtimeAlertService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maker-checker gate for the handful of actions that can do real, hard-to-undo
 * damage to a live PROD pipeline (deleting/stopping a CDC source or Flink
 * job, deleting a data source). EnvironmentGuard already requires
 * realtime:env:prod-operate to even attempt one of these on a PROD-tagged
 * resource - this adds a second requirement on top: the action doesn't
 * actually run until a *different* user (system:approval:handle, not the
 * requester) approves it. DEV resources never reach this class at all, so
 * the day-to-day dev/test workflow gets zero added friction.
 *
 * Each gated controller registers its own apply-callback for the action
 * type(s) it owns (constructor-time, via register()) rather than this
 * service knowing how to delete a CDC source or stop a Flink job itself -
 * those controllers already hold the KafkaConnectClient/FlinkStreamSubmissionClient
 * dependencies needed to do it.
 */
@Service
public class ChangeApprovalService {
    public enum ActionType {
        DATA_SOURCE_DELETE,
        CDC_SOURCE_DELETE,
        CDC_SOURCE_STOP,
        FLINK_STREAM_JOB_DELETE,
        FLINK_STREAM_JOB_STOP,
        FLINK_SQL_JOB_DELETE,
        FLINK_SQL_JOB_STOP
    }

    @FunctionalInterface
    public interface ChangeAction {
        void apply(Long targetId);
    }

    public record GateResult(boolean pending, Long requestId) {
        public static GateResult applied() {
            return new GateResult(false, null);
        }

        public static GateResult pending(Long requestId) {
            return new GateResult(true, requestId);
        }
    }

    private static final String APPROVAL_PERMISSION = "system:approval:handle";

    private static final Map<ActionType, String> ACTION_LABEL = Map.of(
        ActionType.DATA_SOURCE_DELETE, "删除数据源",
        ActionType.CDC_SOURCE_DELETE, "删除 CDC 数据源",
        ActionType.CDC_SOURCE_STOP, "停止 CDC 数据源",
        ActionType.FLINK_STREAM_JOB_DELETE, "删除 Flink 流作业",
        ActionType.FLINK_STREAM_JOB_STOP, "停止 Flink 流作业",
        ActionType.FLINK_SQL_JOB_DELETE, "删除 SQL 流作业",
        ActionType.FLINK_SQL_JOB_STOP, "停止 SQL 流作业"
    );

    private final ChangeRequestMapper changeRequestMapper;
    private final LocalAuthorityService authorityService;
    private final RealtimeAlertService realtimeAlertService;
    private final String approvalCenterUrl;
    private final Map<ActionType, ChangeAction> actions = new ConcurrentHashMap<>();

    public ChangeApprovalService(
        ChangeRequestMapper changeRequestMapper,
        LocalAuthorityService authorityService,
        RealtimeAlertService realtimeAlertService,
        @Value("${platform.web.frontend-url}") String frontendUrl
    ) {
        this.changeRequestMapper = changeRequestMapper;
        this.authorityService = authorityService;
        this.realtimeAlertService = realtimeAlertService;
        this.approvalCenterUrl = frontendUrl + "/system/approval-center";
    }

    public void register(ActionType type, ChangeAction action) {
        actions.put(type, action);
    }

    /** Only PROD-tagged resources defer to approval; anything else runs immediately. */
    public GateResult gate(ActionType type, Long targetId, String environment, String targetSummary) {
        if (!"PROD".equals(environment)) {
            return GateResult.applied();
        }
        ChangeRequestEntity request = new ChangeRequestEntity();
        request.setActionType(type.name());
        request.setTargetId(targetId);
        request.setTargetSummary(targetSummary);
        request.setRequester(currentUsername());
        request.setStatus("PENDING");
        request.setCreatedAt(LocalDateTime.now());
        changeRequestMapper.insert(request);
        notifyApprovers(request);
        return GateResult.pending(request.getId());
    }

    public ChangeRequestEntity approve(Long requestId) {
        ChangeRequestEntity request = requirePending(requestId);
        String approver = requireNotSelfApproval(request);
        ChangeAction action = actions.get(ActionType.valueOf(request.getActionType()));
        // Let a failure here (e.g. Kafka Connect/Flink unreachable) propagate
        // as an error response and leave the request PENDING - the approver's
        // click didn't actually take effect, so marking it APPROVED anyway
        // would silently lose the fact that nothing happened.
        action.apply(request.getTargetId());
        request.setStatus("APPROVED");
        request.setApprover(approver);
        request.setDecidedAt(LocalDateTime.now());
        changeRequestMapper.updateById(request);
        notifyRequester(request, "审批通过：" + describe(request), null);
        return request;
    }

    public ChangeRequestEntity reject(Long requestId, String reason) {
        ChangeRequestEntity request = requirePending(requestId);
        String approver = requireNotSelfApproval(request);
        request.setStatus("REJECTED");
        request.setApprover(approver);
        request.setRejectReason(reason);
        request.setDecidedAt(LocalDateTime.now());
        changeRequestMapper.updateById(request);
        notifyRequester(request, "审批被驳回：" + describe(request), reason);
        return request;
    }

    private void notifyApprovers(ChangeRequestEntity request) {
        List<String> approvers = authorityService.usernamesWithPermission(APPROVAL_PERMISSION).stream()
            .filter(username -> !username.equals(request.getRequester()))
            .toList();
        realtimeAlertService.notifyMultiple(
            approvers,
            "有生产环境变更待审批",
            request.getRequester() + " 申请" + describe(request) + "，请前往审批中心处理",
            "APPROVAL_PENDING",
            approvalCenterUrl
        );
    }

    private void notifyRequester(ChangeRequestEntity request, String title, String reason) {
        realtimeAlertService.notifyMultiple(
            List.of(request.getRequester()),
            title,
            reason != null && !reason.isBlank() ? "驳回理由：" + reason : null,
            "APPROVAL_DECIDED",
            approvalCenterUrl
        );
    }

    private String describe(ChangeRequestEntity request) {
        String label = ACTION_LABEL.getOrDefault(ActionType.valueOf(request.getActionType()), request.getActionType());
        return label + (request.getTargetSummary() != null ? "（" + request.getTargetSummary() + "）" : "");
    }

    private String requireNotSelfApproval(ChangeRequestEntity request) {
        String approver = currentUsername();
        if (request.getRequester().equals(approver)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能审批自己发起的申请，需要另一名审批人处理");
        }
        return approver;
    }

    private ChangeRequestEntity requirePending(Long requestId) {
        ChangeRequestEntity request = changeRequestMapper.selectById(requestId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批申请不存在");
        }
        if (!"PENDING".equals(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该申请已被处理，无法重复操作");
        }
        return request;
    }

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
