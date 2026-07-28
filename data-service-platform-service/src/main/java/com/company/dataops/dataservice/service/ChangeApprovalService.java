package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.ChangeRequestRecord;
import com.company.dataops.dataservice.repository.ChangeApprovalRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChangeApprovalService {
    private final ChangeApprovalRepository repository;
    private final Map<String, ChangeExecutor> executors = new ConcurrentHashMap<>();

    public ChangeApprovalService(ChangeApprovalRepository repository) {
        this.repository = repository;
    }

    public void register(String actionType, ChangeExecutor executor) {
        if (executors.putIfAbsent(actionType, executor) != null) {
            throw new IllegalStateException("Duplicate change executor: " + actionType);
        }
    }

    public List<ChangeRequestRecord> list(int limit) {
        return repository.findAll(Math.min(Math.max(limit, 1), 500));
    }

    public ChangeRequestRecord submit(
        String actionType,
        String targetType,
        long targetId,
        String targetSummary,
        String environment,
        String payloadJson,
        String requester
    ) {
        if (!executors.containsKey(actionType)) {
            throw new IllegalStateException("No executor registered for " + actionType);
        }
        return repository.create(
            actionType,
            targetType,
            targetId,
            targetSummary,
            environment,
            payloadJson,
            requester
        );
    }

    @Transactional
    public ChangeRequestRecord approve(long id, String actor, String comment) {
        ChangeRequestRecord request = pending(id);
        if (request.requester().equals(actor)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Requester cannot approve their own production change"
            );
        }
        ChangeExecutor executor = executors.get(request.actionType());
        if (executor == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Change executor is unavailable");
        }
        executor.execute(request, actor);
        repository.decide(id, "APPROVED", actor, comment);
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public ChangeRequestRecord reject(long id, String actor, String comment) {
        ChangeRequestRecord request = pending(id);
        if (request.requester().equals(actor)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Requester cannot reject their own production change"
            );
        }
        repository.decide(id, "REJECTED", actor, comment);
        return repository.findById(id).orElseThrow();
    }

    private ChangeRequestRecord pending(long id) {
        ChangeRequestRecord request = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Change request not found"));
        if (!"PENDING".equals(request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Change request is no longer pending");
        }
        return request;
    }

    @FunctionalInterface
    public interface ChangeExecutor {
        void execute(ChangeRequestRecord request, String approver);
    }
}
