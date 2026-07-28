package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ApiLifecyclePolicyTest {
    private ApiLifecyclePolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ApiLifecyclePolicy();
    }

    @Test
    void pendingVersionCannotBeEdited() {
        assertThrows(
            ResponseStatusException.class,
            () -> policy.assertEditable("PENDING_APPROVAL")
        );
        assertDoesNotThrow(() -> policy.assertEditable("DRAFT"));
    }

    @Test
    void onlyDraftCanBeSubmitted() {
        assertDoesNotThrow(() -> policy.assertSubmittable("DRAFT"));
        assertThrows(
            ResponseStatusException.class,
            () -> policy.assertSubmittable("REJECTED")
        );
    }

    @Test
    void requiresPendingStateAndDifferentReviewer() {
        assertDoesNotThrow(() ->
            policy.assertReviewable("PENDING_APPROVAL", "developer", "approver")
        );
        assertThrows(
            ResponseStatusException.class,
            () -> policy.assertReviewable("PENDING_APPROVAL", "developer", "developer")
        );
        assertThrows(
            ResponseStatusException.class,
            () -> policy.assertReviewable("DRAFT", "developer", "approver")
        );
    }

    @Test
    void rollbackOnlyAcceptsPublishedHistory() {
        assertDoesNotThrow(() -> policy.assertRollbackSource("PUBLISHED"));
        assertDoesNotThrow(() -> policy.assertRollbackSource("ARCHIVED"));
        assertThrows(
            ResponseStatusException.class,
            () -> policy.assertRollbackSource("DRAFT")
        );
    }
}
