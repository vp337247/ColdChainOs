package com.coldchainos.shipment.infrastructure.external;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resilient implementation of external compliance clearinghouse.
 * Protected by Resilience4j Circuit Breaker and Retry decorators.
 */
@Slf4j
@Service
public class ExternalComplianceClearinghouseClient implements ComplianceClearinghouseClient {

    private final AtomicInteger invocationCount = new AtomicInteger(0);
    private final AtomicBoolean forceFailure = new AtomicBoolean(false);
    private final AtomicBoolean simulateRejection = new AtomicBoolean(false);
    private final AtomicInteger transientFailuresRemaining = new AtomicInteger(0);

    @Override
    @CircuitBreaker(name = "complianceClearinghouse")
    @Retry(name = "complianceClearinghouse", fallbackMethod = "fallbackClearinghouse")
    public ComplianceAssessment verifyRegulatoryClearance(String tenantId, String shipmentId) {
        int currentAttempt = invocationCount.incrementAndGet();

        if (forceFailure.get()) {
            log.warn("[Clearinghouse] Outage forced for shipment {} on attempt {}", shipmentId, currentAttempt);
            throw new ExternalServiceException("Clearinghouse 503 Gateway Outage");
        }

        if (simulateRejection.get()) {
            log.warn("[Clearinghouse] Regulatory clearance rejected for shipment {} on attempt {}", shipmentId, currentAttempt);
            return new ComplianceAssessment(
                shipmentId,
                ComplianceStatus.REJECTED,
                "CLR-REJECTED-" + shipmentId,
                false,
                "Customs clearinghouse rejected clearance: missing export temperature log"
            );
        }

        if (transientFailuresRemaining.get() > 0) {
            transientFailuresRemaining.decrementAndGet();
            log.warn("[Clearinghouse] Simulated transient timeout for shipment {} on attempt {}", shipmentId, currentAttempt);
            throw new ExternalServiceException("Clearinghouse 504 Gateway Timeout (transient)");
        }

        log.info("[Clearinghouse] Verified clearance for shipment {} on attempt {}", shipmentId, currentAttempt);
        return new ComplianceAssessment(
            shipmentId,
            ComplianceStatus.APPROVED,
            "CLR-REAL-" + shipmentId,
            false,
            "Direct verification approved by clearinghouse gateway"
        );
    }

    /**
     * Fallback method executed when the Circuit Breaker is OPEN or retries have been exhausted.
     */
    public ComplianceAssessment fallbackClearinghouse(String tenantId, String shipmentId, Throwable throwable) {
        log.warn("[Clearinghouse Fallback] Invoked for shipment {}. Exception: {}", shipmentId, throwable.getMessage());
        return new ComplianceAssessment(
            shipmentId,
            ComplianceStatus.PROVISIONAL_FALLBACK,
            "CLR-FALLBACK-PROVISIONAL",
            true,
            "Degraded clearance: Circuit breaker fallback triggered due to: " + throwable.getMessage()
        );
    }

    public void setSimulateRejection(boolean reject) {
        this.simulateRejection.set(reject);
    }

    public void setForceFailure(boolean fail) {
        this.forceFailure.set(fail);
    }

    public void setTransientFailures(int count) {
        this.transientFailuresRemaining.set(count);
    }

    public int getInvocationCount() {
        return invocationCount.get();
    }

    public void reset() {
        this.invocationCount.set(0);
        this.forceFailure.set(false);
        this.simulateRejection.set(false);
        this.transientFailuresRemaining.set(0);
    }
}
