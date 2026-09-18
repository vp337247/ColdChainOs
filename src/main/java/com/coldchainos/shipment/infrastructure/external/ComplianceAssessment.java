package com.coldchainos.shipment.infrastructure.external;

/**
 * Result of regulatory compliance clearinghouse verification.
 *
 * @param shipmentId       identifier of the evaluated shipment
 * @param status           approval or provisional fallback status
 * @param clearinghouseRef tracking reference code from clearinghouse
 * @param isFallback       true if fulfilled by local circuit breaker fallback
 * @param notes            diagnostic notes or failure rationale
 */
public record ComplianceAssessment(
    String shipmentId,
    ComplianceStatus status,
    String clearinghouseRef,
    boolean isFallback,
    String notes
) {}
