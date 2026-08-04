package com.company.dataops.console.service.coordination;

import com.company.dataops.console.mapper.PlatformLeaseMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlatformLeaseService {
    private final PlatformLeaseMapper mapper;
    private final String instanceId = UUID.randomUUID().toString();

    public PlatformLeaseService(PlatformLeaseMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Lease> tryAcquire(String name, Duration duration) {
        mapper.ensureExists(name);
        if (mapper.acquire(name, instanceId, Math.max(1, duration.toSeconds())) != 1) {
            return Optional.empty();
        }
        Long token = mapper.fencingToken(name, instanceId);
        return token == null ? Optional.empty() : Optional.of(new Lease(name, instanceId, token));
    }

    public void release(Lease lease) {
        mapper.release(lease.name(), lease.owner(), lease.fencingToken());
    }

    public record Lease(String name, String owner, long fencingToken) {
    }
}
