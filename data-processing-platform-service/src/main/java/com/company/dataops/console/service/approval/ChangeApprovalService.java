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
 * Maker-checker gate for actions that need a second pair of eyes before
 * taking effect. Two families:
 *
 * - Environment-conditional (gate()): deleting/stopping a CDC source, Flink
 *   job, or data source only defers to approval when the target is
 *   PROD-tagged - EnvironmentGuard already requires realtime:env:prod-operate
 *   to even attempt one of these, this adds the requirement that the action
 *   doesn't actually run until a *different* user (system:approval:handle,
 *   not the requester) approves it. DEV resources never reach this class at
 *   all, so the day-to-day dev/test workflow gets zero added friction.
 * - Always-on (gateAlways()/gateAlwaysWithPayload()): role permission
 *   changes and user disable/password-reset have no DEV/PROD distinction to
 *   condition on - they're sensitive regardless of environment, so every
 *   call defers, no immediate-path fallback.
 *
 * Each gated controller registers its own apply-callback for the action
 * type(s) it owns (constructor-time, via register()/registerWithPayload())
 * rather than this service knowing how to delete a CDC source or reassign a
 * role's permissions itself - those controllers already hold the mapper/
 * client dependencies needed to do it.
 */
@Service
public class ChangeApprovalService {
    public enum ActionType {
        DATA_SOURCE_DELETE,
        CDC_SOURCE_DELETE,
        CDC_SOURCE_STOP,
        FLINK_STREAM_JOB_DELETE,
        FLINK_STREAM_JOB_STOP,
        FLINK_STREAM_JOB_UPGRADE,
        FLINK_STREAM_JOB_ROLLBACK,
        FLINK_SQL_JOB_DELETE,
        FLINK_SQL_JOB_STOP,
        FLINK_SQL_JOB_REPLAY,
        FLINK_SQL_JOB_ROLLBACK,
        ROLE_PERMISSION_UPDATE,
        USER_DISABLE,
        USER_PASSWORD_RESET,
        ENCRYPTION_KEY_ROTATE
    }

    @FunctionalInterface
    public interface ChangeAction {
        void apply(Long targetId);
    }

    /**
     * Variant for actions that need to carry the *proposed* new state
     * forward until approved (a role's new menu ID list, a user's new
     * password hash) - unlike delete/stop, there's nothing on the target
     * row itself to re-derive that state from.
     */
    @FunctionalInterface
    public interface PayloadChangeAction {
        void apply(Long targetId, String payload);
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

    // Map.of() tops out at 10 key-value pairs - this has 11, so it needs the
    // explicit Map.entry()/ofEntries() form instead.
    private static final Map<ActionType, String> ACTION_LABEL = Map.ofEntries(
        Map.entry(ActionType.DATA_SOURCE_DELETE, "删除数据源"),
        Map.entry(ActionType.CDC_SOURCE_DELETE, "删除 CDC 数据源"),
        Map.entry(ActionType.CDC_SOURCE_STOP, "停止 CDC 数据源"),
        Map.entry(ActionType.FLINK_STREAM_JOB_DELETE, "删除 Flink 流作业"),
        Map.entry(ActionType.FLINK_STREAM_JOB_STOP, "停止 Flink 流作业"),
        Map.entry(ActionType.FLINK_STREAM_JOB_UPGRADE, "滚动升级 Flink 流作业"),
        Map.entry(ActionType.FLINK_STREAM_JOB_ROLLBACK, "回滚 Flink 流作业"),
        Map.entry(ActionType.FLINK_SQL_JOB_DELETE, "删除 SQL 流作业"),
        Map.entry(ActionType.FLINK_SQL_JOB_STOP, "停止 SQL 流作业"),
        Map.entry(ActionType.FLINK_SQL_JOB_REPLAY, "重放 SQL 流作业"),
        Map.entry(ActionType.FLINK_SQL_JOB_ROLLBACK, "回滚 SQL 流作业"),
        Map.entry(ActionType.ROLE_PERMISSION_UPDATE, "修改角色权限"),
        Map.entry(ActionType.USER_DISABLE, "禁用用户"),
        Map.entry(ActionType.USER_PASSWORD_RESET, "重置用户密码"),
        Map.entry(ActionType.ENCRYPTION_KEY_ROTATE, "轮换加密密钥")
    );

    private final ChangeRequestMapper changeRequestMapper;
    private final LocalAuthorityService authorityService;
    private final RealtimeAlertService realtimeAlertService;
    private final String approvalCenterUrl;
    private final Map<ActionType, ChangeAction> actions = new ConcurrentHashMap<>();
    private final Map<ActionType, PayloadChangeAction> payloadActions = new ConcurrentHashMap<>();

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

    public void registerWithPayload(ActionType type, PayloadChangeAction action) {
        payloadActions.put(type, action);
    }

    /** Only PROD-tagged resources defer to approval; anything else runs immediately. */
    public GateResult gate(ActionType type, Long targetId, String environment, String targetSummary) {
        if (!"PROD".equals(environment)) {
            return GateResult.applied();
        }
        return createPendingRequest(type, targetId, null, targetSummary);
    }

    /**
     * For actions with no environment concept to check - role permission
     * changes and user account security actions are always sensitive, so
     * unlike gate() there's no immediate-path fallback here at all.
     */
    public GateResult gateAlways(ActionType type, Long targetId, String targetSummary) {
        return createPendingRequest(type, targetId, null, targetSummary);
    }

    public GateResult gateAlwaysWithPayload(ActionType type, Long targetId, String payload, String targetSummary) {
        return createPendingRequest(type, targetId, payload, targetSummary);
    }

    /** gate()'s environment-conditional check, combined with gateAlwaysWithPayload()'s payload carrying - for actions like a rolling upgrade that need both. */
    public GateResult gateWithPayload(ActionType type, Long targetId, String environment, String payload, String targetSummary) {
        if (!"PROD".equals(environment)) {
            return GateResult.applied();
        }
        return createPendingRequest(type, targetId, payload, targetSummary);
    }

    private GateResult createPendingRequest(ActionType type, Long targetId, String payload, String targetSummary) {
        ChangeRequestEntity request = new ChangeRequestEntity();
        request.setActionType(type.name());
        request.setTargetId(targetId);
        request.setTargetSummary(targetSummary);
        request.setPayload(payload);
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
        // Let a failure here (e.g. Kafka Connect/Flink unreachable) propagate
        // as an error response and leave the request PENDING - the approver's
        // click didn't actually take effect, so marking it APPROVED anyway
        // would silently lose the fact that nothing happened.
        applyAction(request);
        request.setStatus("APPROVED");
        request.setApprover(approver);
        request.setDecidedAt(LocalDateTime.now());
        changeRequestMapper.updateById(request);
        notifyRequester(request, "审批通过：" + describe(request), null);
        return request;
    }

    private void applyAction(ChangeRequestEntity request) {
        ActionType type = ActionType.valueOf(request.getActionType());
        PayloadChangeAction payloadAction = payloadActions.get(type);
        if (payloadAction != null) {
            payloadAction.apply(request.getTargetId(), request.getPayload());
            return;
        }
        actions.get(type).apply(request.getTargetId());
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
