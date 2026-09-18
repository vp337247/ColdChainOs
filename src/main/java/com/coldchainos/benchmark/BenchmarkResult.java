package com.coldchainos.benchmark;

/**
 * Immutable report capturing latency distribution and throughput metrics for a benchmark run.
 */
public record BenchmarkResult(
    String benchmarkName,
    int totalOperations,
    long durationMillis,
    double throughputOpsPerSec,
    double p50LatencyMs,
    double p95LatencyMs,
    double p99LatencyMs,
    int successfulOperations,
    int failedOperations,
    String notes
) {
    public static BenchmarkResult of(
        String name,
        int total,
        long durationMillis,
        double[] latenciesMs,
        int success,
        int failed,
        String notes
    ) {
        double throughput = durationMillis > 0
            ? (total * 1000.0) / durationMillis
            : total;

        java.util.Arrays.sort(latenciesMs);
        double p50 = latenciesMs.length > 0 ? latenciesMs[(int) (latenciesMs.length * 0.50)] : 0.0;
        double p95 = latenciesMs.length > 0 ? latenciesMs[(int) (latenciesMs.length * 0.95)] : 0.0;
        double p99 = latenciesMs.length > 0 ? latenciesMs[(int) (latenciesMs.length * 0.99)] : 0.0;

        return new BenchmarkResult(
            name,
            total,
            durationMillis,
            Math.round(throughput * 100.0) / 100.0,
            Math.round(p50 * 100.0) / 100.0,
            Math.round(p95 * 100.0) / 100.0,
            Math.round(p99 * 100.0) / 100.0,
            success,
            failed,
            notes
        );
    }
}
