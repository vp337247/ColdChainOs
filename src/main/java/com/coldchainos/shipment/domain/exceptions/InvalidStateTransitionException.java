package com.coldchainos.shipment.domain.exceptions;

import com.coldchainos.shared.domain.DomainException;
import com.coldchainos.shipment.domain.ShipmentStatus;

public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(ShipmentStatus from, ShipmentStatus to, String reason) {
        super(String.format("Cannot transition shipment from %s to %s: %s", from, to, reason));
    }

    public InvalidStateTransitionException(ShipmentStatus from, ShipmentStatus to) {
        this(from, to, "Transition not permitted by state machine invariants");
    }
}
