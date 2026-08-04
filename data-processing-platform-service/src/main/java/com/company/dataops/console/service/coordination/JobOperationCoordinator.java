package com.company.dataops.console.service.coordination;

import com.company.dataops.console.mapper.JobOperationRequestMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class JobOperationCoordinator {
    private final PlatformLeaseService leaseService;
    private final JobOperationRequestMapper requestMapper;

    public JobOperationCoordinator(PlatformLeaseService leaseService, JobOperationRequestMapper requestMapper) {
        this.leaseService = leaseService;
        this.requestMapper = requestMapper;
    }

    public <T> T execute(String entityType, Long entityId, String operationType,
                         String idempotencyKey, Duration timeout, Supplier<T> action) {
        String lockName = "job-operation:" + entityType + ":" + entityId;
        PlatformLeaseService.Lease lease = leaseService.tryAcquire(lockName, timeout)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "该作业正在执行其他操作，请稍后重试"));
        String key = normalizeKey(idempotencyKey);
        try {
            if (requestMapper.register(key, entityType, entityId, operationType, lease.fencingToken()) != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该幂等请求已经处理，不能重复执行");
            }
            try {
                T result = action.get();
                requestMapper.markSucceeded(key, lease.fencingToken());
                return result;
            } catch (RuntimeException exception) {
                requestMapper.markFailed(key, lease.fencingToken(), abbreviate(exception.getMessage()));
                throw exception;
            }
        } finally {
            leaseService.release(lease);
        }
    }

    public void execute(String entityType, Long entityId, String operationType, String idempotencyKey,
                        Duration timeout, Runnable action) {
        execute(entityType, entityId, operationType, idempotencyKey, timeout, () -> {
            action.run();
            return null;
        });
    }

    private String normalizeKey(String key) {
        return key == null || key.isBlank() ? UUID.randomUUID().toString() : key.trim();
    }

    private String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
