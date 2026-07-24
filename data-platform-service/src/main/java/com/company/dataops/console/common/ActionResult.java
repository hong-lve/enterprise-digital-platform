package com.company.dataops.console.common;

/**
 * Response body for a mutating endpoint gated by ChangeApprovalService: either
 * the action ran immediately (DEV resource, or no gate applies) or it was
 * deferred pending a second person's approval (PROD resource).
 */
public record ActionResult(String status, Long approvalRequestId) {
    public static ActionResult applied() {
        return new ActionResult("APPLIED", null);
    }

    public static ActionResult pending(Long approvalRequestId) {
        return new ActionResult("PENDING_APPROVAL", approvalRequestId);
    }
}
