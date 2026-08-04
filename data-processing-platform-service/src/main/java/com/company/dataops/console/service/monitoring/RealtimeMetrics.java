package com.company.dataops.console.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class RealtimeMetrics {
    private final MeterRegistry registry;

    public RealtimeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void jobSubmission(String jobType, boolean success) {
        Counter.builder("realtime_job_submissions_total")
            .tag("job_type", jobType).tag("outcome", success ? "success" : "failure")
            .register(registry).increment();
    }

    public void checkpoint(String status, Long durationMs) {
        Counter.builder("realtime_checkpoints_total").tag("status", normalize(status)).register(registry).increment();
        if (durationMs != null) {
            DistributionSummary.builder("realtime_checkpoint_duration_milliseconds")
                .baseUnit("milliseconds").register(registry).record(durationMs);
        }
    }

    public void lag(String sourceType, Long value) {
        if (value != null) {
            DistributionSummary.builder("realtime_pipeline_lag")
                .tag("source_type", sourceType).register(registry).record(Math.max(0, value));
        }
    }

    public void scheduler(String name, long elapsedNanos, boolean success) {
        Timer.builder("realtime_scheduler_duration")
            .tag("scheduler", name).tag("outcome", success ? "success" : "failure")
            .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
            .register(registry).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void alertDelivery(boolean success) {
        Counter.builder("realtime_alert_deliveries_total")
            .tag("outcome", success ? "success" : "failure").register(registry).increment();
    }

    public void dataQuality(String result) {
        Counter.builder("realtime_data_quality_checks_total")
            .tag("result", normalize(result)).register(registry).increment();
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.toLowerCase(Locale.ROOT);
    }
}
