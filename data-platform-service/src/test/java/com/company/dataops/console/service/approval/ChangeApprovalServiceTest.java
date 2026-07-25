package com.company.dataops.console.service.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.console.entity.ChangeRequestEntity;
import com.company.dataops.console.mapper.ChangeRequestMapper;
import com.company.dataops.console.security.LocalAuthorityService;
import com.company.dataops.console.service.RealtimeAlertService;
import com.company.dataops.console.service.approval.ChangeApprovalService.ActionType;
import com.company.dataops.console.service.approval.ChangeApprovalService.ChangeAction;
import com.company.dataops.console.service.approval.ChangeApprovalService.GateResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * ChangeApprovalService is the maker-checker gate for PROD delete/stop
 * actions - its riskiest properties are exactly the ones that don't show up
 * in a manual click-through: self-approval must be blocked *before* the
 * underlying action ever runs (not just before the DB row flips to
 * APPROVED), a DEV resource must never touch the change_request table or
 * fire a notification at all, and an already-decided request must not be
 * re-appliable by hitting approve()/reject() twice.
 */
class ChangeApprovalServiceTest {
    private static final String APPROVAL_PERMISSION = "system:approval:handle";

    private final Map<Long, ChangeRequestEntity> store = new HashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private ChangeRequestMapper changeRequestMapper;
    private LocalAuthorityService authorityService;
    private RealtimeAlertService realtimeAlertService;
    private ChangeApprovalService service;

    @BeforeEach
    void setUp() {
        store.clear();
        nextId.set(1);

        changeRequestMapper = mock(ChangeRequestMapper.class);
        doAnswer(invocation -> {
            ChangeRequestEntity entity = invocation.getArgument(0);
            entity.setId(nextId.getAndIncrement());
            store.put(entity.getId(), entity);
            return 1;
        }).when(changeRequestMapper).insert(any(ChangeRequestEntity.class));
        when(changeRequestMapper.selectById(anyLong())).thenAnswer(invocation -> store.get((Long) invocation.getArgument(0)));
        doAnswer(invocation -> {
            ChangeRequestEntity entity = invocation.getArgument(0);
            store.put(entity.getId(), entity);
            return 1;
        }).when(changeRequestMapper).updateById(any(ChangeRequestEntity.class));

        authorityService = mock(LocalAuthorityService.class);
        when(authorityService.usernamesWithPermission(APPROVAL_PERMISSION)).thenReturn(List.of("admin", "approver"));

        realtimeAlertService = mock(RealtimeAlertService.class);

        service = new ChangeApprovalService(changeRequestMapper, authorityService, realtimeAlertService, "http://localhost:5178");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    @Test
    void gateOnDevResourceAppliesImmediatelyWithoutTouchingTheRequestTableOrNotifying() {
        authenticateAs("admin");
        GateResult result = service.gate(ActionType.CDC_SOURCE_DELETE, 10L, "DEV", "CDC 数据源: dev-test");

        assertFalse(result.pending());
        assertNull(result.requestId());
        verify(changeRequestMapper, never()).insert(any(ChangeRequestEntity.class));
        verify(realtimeAlertService, never()).notifyMultiple(any(), any(), any(), any(), any());
    }

    @Test
    void gateOnProdResourceCreatesAPendingRequestAndNotifiesApproversExcludingTheRequester() {
        authenticateAs("admin");
        GateResult result = service.gate(ActionType.CDC_SOURCE_DELETE, 10L, "PROD", "CDC 数据源: prod-test");

        assertTrue(result.pending());
        assertEquals(1L, result.requestId());
        ChangeRequestEntity stored = store.get(1L);
        assertEquals("PENDING", stored.getStatus());
        assertEquals("admin", stored.getRequester());

        verify(realtimeAlertService).notifyMultiple(
            eq(List.of("approver")), // "admin" (the requester) must be excluded even though it also holds the permission
            eq("有生产环境变更待审批"),
            contains("admin"),
            eq("APPROVAL_PENDING"),
            any()
        );
    }

    @Test
    void gateExcludesTheRequesterEvenWhenTheyAreTheOnlyApprover() {
        when(authorityService.usernamesWithPermission(APPROVAL_PERMISSION)).thenReturn(List.of("admin"));
        authenticateAs("admin");

        service.gate(ActionType.CDC_SOURCE_DELETE, 10L, "PROD", "x");

        verify(realtimeAlertService).notifyMultiple(eq(List.of()), any(), any(), eq("APPROVAL_PENDING"), any());
    }

    @Test
    void approveInvokesTheRegisteredActionAndNotifiesTheRequester() {
        ChangeAction action = mock(ChangeAction.class);
        service.register(ActionType.CDC_SOURCE_DELETE, action);

        authenticateAs("admin");
        GateResult gate = service.gate(ActionType.CDC_SOURCE_DELETE, 10L, "PROD", "CDC 数据源: prod-test");

        authenticateAs("approver");
        ChangeRequestEntity approved = service.approve(gate.requestId());

        assertEquals("APPROVED", approved.getStatus());
        assertEquals("approver", approved.getApprover());
        verify(action).apply(10L);
        verify(realtimeAlertService).notifyMultiple(eq(List.of("admin")), contains("审批通过"), isNull(), eq("APPROVAL_DECIDED"), any());
    }

    @Test
    void rejectNeverInvokesTheActionAndNotifiesTheRequesterWithTheReason() {
        ChangeAction action = mock(ChangeAction.class);
        service.register(ActionType.CDC_SOURCE_DELETE, action);

        authenticateAs("admin");
        GateResult gate = service.gate(ActionType.CDC_SOURCE_DELETE, 10L, "PROD", "CDC 数据源: prod-test");

        authenticateAs("approver");
        ChangeRequestEntity rejected = service.reject(gate.requestId(), "目前不方便下线");

        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("目前不方便下线", rejected.getRejectReason());
        verify(action, never()).apply(any());
        verify(realtimeAlertService).notifyMultiple(eq(List.of("admin")), contains("驳回"), contains("目前不方便下线"), eq("APPROVAL_DECIDED"), any());
    }

    @Test
    void approvingYourOwnRequestIsForbiddenAndNeverRunsTheAction() {
        ChangeAction action = mock(ChangeAction.class);
        service.register(ActionType.CDC_SOURCE_DELETE, action);

        authenticateAs("admin");
        GateResult gate = service.gate(ActionType.CDC_SOURCE_DELETE, 10L, "PROD", "x");

        // Still authenticated as "admin" - the same user who filed the request.
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.approve(gate.requestId()));
        assertEquals(403, exception.getStatusCode().value());
        verify(action, never()).apply(any());
        assertEquals("PENDING", store.get(gate.requestId()).getStatus());
    }

    @Test
    void rejectingYourOwnRequestIsAlsoForbidden() {
        authenticateAs("admin");
        GateResult gate = service.gate(ActionType.CDC_SOURCE_DELETE, 10L, "PROD", "x");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.reject(gate.requestId(), "no"));
        assertEquals(403, exception.getStatusCode().value());
        assertEquals("PENDING", store.get(gate.requestId()).getStatus());
    }

    @Test
    void approvingAnAlreadyDecidedRequestFailsAndDoesNotReapplyTheAction() {
        ChangeAction action = mock(ChangeAction.class);
        service.register(ActionType.CDC_SOURCE_DELETE, action);

        authenticateAs("admin");
        GateResult gate = service.gate(ActionType.CDC_SOURCE_DELETE, 10L, "PROD", "x");

        authenticateAs("approver");
        service.approve(gate.requestId());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.approve(gate.requestId()));
        assertEquals(400, exception.getStatusCode().value());
        verify(action, times(1)).apply(10L);
    }

    @Test
    void approvingAnUnknownRequestIdThrowsNotFound() {
        authenticateAs("approver");
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.approve(999L));
        assertEquals(404, exception.getStatusCode().value());
    }
}
