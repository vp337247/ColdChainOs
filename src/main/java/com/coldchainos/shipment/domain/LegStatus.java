package com.coldchainos.shipment.domain;

/**
 * Lifecycle states for individual transit legs.
 */
public enum LegStatus {
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
