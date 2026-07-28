package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.dataops.dataservice.domain.ChangeRequestRecord;
import com.company.dataops.dataservice.repository.ChangeApprovalRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ChangeApprovalServiceTest {
    @Test
    void appliesRegisteredChangeBeforeMarkingItApproved() {
        ChangeApprovalRepository repository = mock(ChangeApprovalRepository.class);
        ChangeApprovalService service = new ChangeApprovalService(repository);
        ChangeRequestRecord pending = request("requester", "PENDING");
        ChangeRequestRecord approved = request("requester", "APPROVED");
        AtomicBoolean executed = new AtomicBoolean();
        service.register("DATASET_POLICY_UPDATE", (request, approver) -> executed.set(true));
        when(repository.findById(1L))
            .thenReturn(Optional.of(pending))
            .thenReturn(Optional.of(approved));

        ChangeRequestRecord result = service.approve(1L, "approver", "checked");

        assertEquals("APPROVED", result.status());
        assertEquals(true, executed.get());
        verify(repository).decide(1L, "APPROVED", "approver", "checked");
    }

    @Test
    void preventsRequesterFromApprovingOwnProductionChange() {
        ChangeApprovalRepository repository = mock(ChangeApprovalRepository.class);
        ChangeApprovalService service = new ChangeApprovalService(repository);
        service.register("DATASET_POLICY_UPDATE", (request, approver) -> { });
        when(repository.findById(1L)).thenReturn(Optional.of(request("requester", "PENDING")));

        assertThrows(
            ResponseStatusException.class,
            () -> service.approve(1L, "requester", null)
        );
    }

    private ChangeRequestRecord request(String requester, String status) {
        return new ChangeRequestRecord(
            1L,
            "DATASET_POLICY_UPDATE",
            "DATASET",
            2L,
            "orders access policy",
            "PROD",
            "{}",
            requester,
            status,
            "APPROVED".equals(status) ? "approver" : null,
            null,
            null,
            Instant.now(),
            Instant.now()
        );
    }
}
