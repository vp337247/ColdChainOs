package com.coldchainos.shipment.infrastructure.external;

/**
 * Port interface for external regulatory compliance clearinghouses.
 */
public interface ComplianceClearinghouseClient {

    /**
     * Verifies regulatory and customs clearance with an external gateway.
     */
    ComplianceAssessment verifyRegulatoryClearance(String tenantId, String shipmentId);
}
