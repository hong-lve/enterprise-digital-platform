package com.company.dataops.dataservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class ApiMetricsService {
    private final MeterRegistry meterRegistry;

    public ApiMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(
        long apiId,
        int statusCode,
        long elapsedMs,
        String cacheStatus,
        boolean degraded
    ) {
        String api = String.valueOf(apiId);
        String outcome = statusCode < 400 ? "SUCCESS" : "ERROR";
        Counter.builder("data_service_api_requests")
            .description("Data service API requests")
            .tag("api_id", api)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
        Timer.builder("data_service_api_latency")
            .description("Data service API latency")
            .tag("api_id", api)
            .publishPercentileHistogram()
            .register(meterRegistry)
            .record(Duration.ofMillis(elapsedMs));
        Counter.builder("data_service_api_cache")
            .description("Data service API cache outcomes")
            .tag("api_id", api)
            .tag("status", cacheStatus == null ? "NONE" : cacheStatus)
            .tag("degraded", String.valueOf(degraded))
            .register(meterRegistry)
            .increment();
    }
}
