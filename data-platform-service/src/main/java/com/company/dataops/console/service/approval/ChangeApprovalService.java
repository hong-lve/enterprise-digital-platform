package com.company.dataops.console.service.approval;

import com.company.dataops.console.entity.ChangeRequestEntity;
import com.company.dataops.console.mapper.ChangeRequestMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private final ChangeRequestMapper changeRequestMapper;
    private final Map<ActionType, ChangeAction> actions = new ConcurrentHashMap<>();

    public ChangeApprovalService(ChangeRequestMapper changeRequestMapper) {
        this.changeRequestMapper = changeRequestMapper;
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
        return request;
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
