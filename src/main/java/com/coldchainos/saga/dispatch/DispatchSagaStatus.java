package com.coldchainos.saga.dispatch;

/**
 * State machine stages for the shipment dispatch distributed saga.
 */
public enum DispatchSagaStatus {
    STARTED,
    WAREHOUSE_SLOT_RESERVED,
    CARRIER_BOOKED,
    COMPLIANCE_VERIFIED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED_FAILURE
}
