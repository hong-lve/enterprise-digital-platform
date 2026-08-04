package com.company.dataops.console.service.coordination;

import com.company.dataops.console.service.monitoring.RealtimeMetrics;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ClusterSingletonAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterSingletonAspect.class);
    private final PlatformLeaseService leaseService;
    private final RealtimeMetrics metrics;

    public ClusterSingletonAspect(PlatformLeaseService leaseService, RealtimeMetrics metrics) {
        this.leaseService = leaseService;
        this.metrics = metrics;
    }

    @Around("@annotation(singleton)")
    public Object executeOnce(ProceedingJoinPoint joinPoint, ClusterSingleton singleton) throws Throwable {
        var lease = leaseService.tryAcquire("scheduler:" + singleton.value(), Duration.ofSeconds(singleton.lockAtMostSeconds()));
        if (lease.isEmpty()) {
            LOGGER.debug("Skipped scheduler {} because another instance owns the lease", singleton.value());
            return null;
        }
        try {
            long started = System.nanoTime();
            try {
                Object result = joinPoint.proceed();
                metrics.scheduler(singleton.value(), System.nanoTime() - started, true);
                return result;
            } catch (Throwable throwable) {
                metrics.scheduler(singleton.value(), System.nanoTime() - started, false);
                throw throwable;
            }
        } finally {
            leaseService.release(lease.get());
        }
    }
}
