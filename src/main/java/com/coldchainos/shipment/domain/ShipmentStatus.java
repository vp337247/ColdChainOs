package com.coldchainos.shipment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * State machine defining the lifecycle of a Shipment.
 * All transitions are strictly validated to prevent illegal progression.
 */
public enum ShipmentStatus {
    CREATED,
    ASSIGNED,
    IN_TRANSIT,
    AT_PORT,
    WAREHOUSE,
    DELIVERED,
    QUARANTINED,
    CANCELLED;

    public boolean canTransitionTo(ShipmentStatus target) {
        if (target == null || target == this) {
            return false;
        }

        return switch (this) {
            case CREATED -> target == ASSIGNED || target == CANCELLED;
            case ASSIGNED -> target == IN_TRANSIT || target == CANCELLED;
            case IN_TRANSIT -> target == AT_PORT || target == WAREHOUSE || target == DELIVERED || target == QUARANTINED;
            case AT_PORT -> target == IN_TRANSIT || target == WAREHOUSE || target == QUARANTINED;
            case WAREHOUSE -> target == IN_TRANSIT || target == DELIVERED || target == QUARANTINED;
            // QUARANTINED can only be exited via explicit QA release restoration
            case QUARANTINED -> target == IN_TRANSIT || target == AT_PORT || target == WAREHOUSE || target == CANCELLED;
            case DELIVERED, CANCELLED -> false; // Terminal states
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }

    public boolean isQuarantined() {
        return this == QUARANTINED;
    }
}
