package com.coldchainos.shared.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Centralized Micrometer business and operational metrics facade for ColdChainOS.
 * Exposes Prometheus-compatible counters and timers for telemetry streaming,
 * outbox relay processing, temperature excursions, and CQRS read projections.
 */
@Component
public class ColdChainMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter globalOutboxRelayedCounter;
    private final Counter globalTelemetryIngestedCounter;
    private final Counter globalExcursionCounter;
    private final Timer projectionTimer;

    public ColdChainMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.globalOutboxRelayedCounter = Counter.builder("coldchain.outbox.relayed.total")
            .description("Total number of transactional outbox events successfully relayed to Kafka")
            .register(meterRegistry);
        this.globalTelemetryIngestedCounter = Counter.builder("coldchain.telemetry.ingested.total")
            .description("Total number of IoT telemetry frames ingested into hot cache and history")
            .register(meterRegistry);
        this.globalExcursionCounter = Counter.builder("coldchain.excursions.detected.total")
            .description("Total number of cold chain thermal excursions triggering quarantine")
            .register(meterRegistry);
        this.projectionTimer = Timer.builder("coldchain.cqrs.projection.latency")
            .description("Execution latency for updating materialized read summary projections")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
    }

    public void recordOutboxRelayed(String tenantId, int count) {
        if (count <= 0) return;
        globalOutboxRelayedCounter.increment(count);
        meterRegistry.counter("coldchain.outbox.relayed", "tenantId", tenantId).increment(count);
    }

    public void recordTelemetryIngested(String tenantId, boolean excursion) {
        globalTelemetryIngestedCounter.increment();
        meterRegistry.counter("coldchain.telemetry.ingested",
            "tenantId", tenantId,
            "status", excursion ? "EXCURSION" : "NOMINAL"
        ).increment();
    }

    public void recordExcursionDetected(String tenantId, String thermalCategory) {
        globalExcursionCounter.increment();
        meterRegistry.counter("coldchain.excursions.detected",
            "tenantId", tenantId,
            "thermalCategory", thermalCategory
        ).increment();
    }

    public void recordProjectionLatency(long durationMillis) {
        projectionTimer.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTimer(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(projectionTimer);
        }
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
