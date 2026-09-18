package com.coldchainos.benchmark;

import com.coldchainos.audit.application.AuditLedgerService;
import com.coldchainos.audit.application.AuditLedgerVerificationService;
import com.coldchainos.audit.application.TamperVerificationResult;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.ratelimit.RateLimitResult;
import com.coldchainos.shared.ratelimit.TenantRateLimiter;
import com.coldchainos.shipment.application.dto.TelemetryStreamPayload;
import com.coldchainos.shipment.infrastructure.kafka.TelemetryKafkaProducer;
import com.coldchainos.tenant.application.TenantProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production Benchmarking Service for ColdChainOS.
 * Provides real-world stress testing, throughput evaluation, and latency percentile tracking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BenchmarkService {

    private final TelemetryKafkaProducer telemetryKafkaProducer;
    private final TenantRateLimiter tenantRateLimiter;
    private final AuditLedgerService auditLedgerService;
    private final AuditLedgerVerificationService auditLedgerVerificationService;
    private final TenantProvisioningService tenantProvisioningService;

    /**
     * Stress-tests telemetry ingestion throughput over Apache Kafka.
     */
    public BenchmarkResult benchmarkTelemetryStream(TenantId tenantId, int packetCount, int concurrency) {
        log.info("[Benchmark] Starting Telemetry Ingestion Benchmark: {} packets across {} threads for tenant '{}'",
            packetCount, concurrency, tenantId.value());

        tenantProvisioningService.provisionTenant(tenantId, "Benchmark Tenant " + tenantId.value());

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        double[] latencies = new double[packetCount];
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        UUID shipmentId = UUID.randomUUID();
        long startTime = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(packetCount);

        for (int i = 0; i < packetCount; i++) {
            final int index = i;
            executor.submit(() -> {
                long opStart = System.nanoTime();
                try {
                    TelemetryStreamPayload payload = new TelemetryStreamPayload(
                        tenantId.value(),
                        shipmentId,
                        "SENSOR_BM_" + (index % 50),
                        Instant.now(),
                        BigDecimal.valueOf(4.0 + (index % 5) * 0.1),
                        BigDecimal.valueOf(55.0),
                        BigDecimal.valueOf(50.1109),
                        BigDecimal.valueOf(8.6821),
                        BigDecimal.valueOf(95)
                    );

                    telemetryKafkaProducer.sendTelemetry(payload).get(5, TimeUnit.SECONDS);
                    long opDuration = System.nanoTime() - opStart;
                    latencies[index] = opDuration / 1_000_000.0;
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    latencies[index] = -1.0;
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("[Benchmark] Telemetry benchmark timed out after 60 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;
        double[] validLatencies = Arrays.stream(latencies).filter(l -> l >= 0).toArray();

        return BenchmarkResult.of(
            "Kafka Telemetry Ingestion Stream",
            packetCount,
            totalDurationMs,
            validLatencies,
            successCount.get(),
            failureCount.get(),
            "Evaluated with partition affinity, JSON serialization, and broker ack across " + concurrency + " threads"
        );
    }

    /**
     * Benchmarks Redis Token Bucket Rate Limiting saturation and latency.
     */
    public BenchmarkResult benchmarkRateLimiting(TenantId tenantId, int requestCount, long capacity, long refillRate) {
        log.info("[Benchmark] Starting Rate Limiter Benchmark: {} requests against Redis bucket (cap={}, refill={})",
            requestCount, capacity, refillRate);

        tenantRateLimiter.reset(tenantId);

        double[] latencies = new double[requestCount];
        int allowedCount = 0;
        int rejectedCount = 0;

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            long opStart = System.nanoTime();
            RateLimitResult result = tenantRateLimiter.tryAcquire(tenantId, capacity, refillRate, 1);
            long opDuration = System.nanoTime() - opStart;
            latencies[i] = opDuration / 1_000_000.0;

            if (result.allowed()) {
                allowedCount++;
            } else {
                rejectedCount++;
            }
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        return BenchmarkResult.of(
            "Redis Distributed Token Bucket Rate Limiter",
            requestCount,
            totalDurationMs,
            latencies,
            allowedCount,
            rejectedCount,
            String.format("Allowed=%d, Throttled=%d (Enforced deterministic capacity limit)", allowedCount, rejectedCount)
        );
    }

    /**
     * Benchmarks 21 CFR Part 11 Cryptographic Audit Ledger SHA-256 chain traversal and verification.
     */
    public BenchmarkResult benchmarkAuditLedgerVerification(TenantId tenantId, int blockCount) {
        log.info("[Benchmark] Starting Audit Ledger Benchmark: appending {} blocks and verifying SHA-256 chain integrity",
            blockCount);

        tenantProvisioningService.provisionTenant(tenantId, "Audit Benchmark Tenant");

        UUID shipmentId = UUID.randomUUID();

        // 1. Append blocks
        long appendStart = System.currentTimeMillis();
        for (int i = 0; i < blockCount; i++) {
            auditLedgerService.appendEvent(
                tenantId,
                shipmentId,
                "Shipment",
                "TelemetryLoggedEvent",
                "{\"readingIndex\":" + i + ",\"temp\":4.5}",
                "BENCHMARK_RUNNER",
                "SIG_BM_SHA256_" + i
            );
        }
        long appendDurationMs = System.currentTimeMillis() - appendStart;

        // 2. Measure verification throughput
        long verifyStart = System.currentTimeMillis();
        TamperVerificationResult verifyResult = auditLedgerVerificationService.verifyLedgerIntegrity(tenantId);
        long verifyDurationMs = System.currentTimeMillis() - verifyStart;

        double verificationBlocksPerSec = verifyDurationMs > 0
            ? (verifyResult.totalBlocks() * 1000.0) / verifyDurationMs
            : verifyResult.totalBlocks();

        return new BenchmarkResult(
            "21 CFR Part 11 Cryptographic Audit Verification",
            (int) verifyResult.totalBlocks(),
            verifyDurationMs,
            Math.round(verificationBlocksPerSec * 100.0) / 100.0,
            0.0,
            0.0,
            0.0,
            verifyResult.valid() ? (int) verifyResult.totalBlocks() : 0,
            verifyResult.valid() ? 0 : 1,
            String.format("Appended %d blocks in %d ms; Traversed & verified %d SHA-256 blocks in %d ms (Valid=%s)",
                blockCount, appendDurationMs, verifyResult.totalBlocks(), verifyDurationMs, verifyResult.valid())
        );
    }

    /**
     * Executes the complete performance benchmarking suite.
     */
    public Map<String, BenchmarkResult> runFullBenchmarkSuite(TenantId tenantId) {
        Map<String, BenchmarkResult> results = new LinkedHashMap<>();

        results.put("rate_limiter", benchmarkRateLimiting(tenantId, 1000, 100, 50));
        results.put("audit_ledger", benchmarkAuditLedgerVerification(tenantId, 100));
        results.put("telemetry_stream", benchmarkTelemetryStream(tenantId, 500, 5));

        return results;
    }
}
