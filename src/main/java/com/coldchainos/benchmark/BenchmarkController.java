package com.coldchainos.benchmark;

import com.coldchainos.shared.domain.TenantId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API exposing live operational performance and load benchmarks for ColdChainOS.
 */
@RestController
@RequestMapping("/api/v1/benchmarks")
@RequiredArgsConstructor
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    @PostMapping("/telemetry")
    public ResponseEntity<BenchmarkResult> benchmarkTelemetry(
        @RequestParam(defaultValue = "benchmark_tenant") String tenantId,
        @RequestParam(defaultValue = "500") int packets,
        @RequestParam(defaultValue = "5") int concurrency
    ) {
        BenchmarkResult result = benchmarkService.benchmarkTelemetryStream(
            TenantId.of(tenantId),
            packets,
            concurrency
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/rate-limiter")
    public ResponseEntity<BenchmarkResult> benchmarkRateLimiter(
        @RequestParam(defaultValue = "benchmark_tenant") String tenantId,
        @RequestParam(defaultValue = "1000") int requests,
        @RequestParam(defaultValue = "100") long capacity,
        @RequestParam(defaultValue = "50") long refillRate
    ) {
        BenchmarkResult result = benchmarkService.benchmarkRateLimiting(
            TenantId.of(tenantId),
            requests,
            capacity,
            refillRate
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/audit-ledger")
    public ResponseEntity<BenchmarkResult> benchmarkAuditLedger(
        @RequestParam(defaultValue = "benchmark_tenant") String tenantId,
        @RequestParam(defaultValue = "100") int blocks
    ) {
        BenchmarkResult result = benchmarkService.benchmarkAuditLedgerVerification(
            TenantId.of(tenantId),
            blocks
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/full-suite")
    public ResponseEntity<Map<String, BenchmarkResult>> runFullSuite(
        @RequestParam(defaultValue = "benchmark_tenant") String tenantId
    ) {
        Map<String, BenchmarkResult> results = benchmarkService.runFullBenchmarkSuite(TenantId.of(tenantId));
        return ResponseEntity.ok(results);
    }
}
