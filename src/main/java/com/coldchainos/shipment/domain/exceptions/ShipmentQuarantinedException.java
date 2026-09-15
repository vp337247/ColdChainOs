package com.coldchainos.shipment.domain.exceptions;

import com.coldchainos.shared.domain.DomainException;
import com.coldchainos.shipment.domain.ShipmentId;

public class ShipmentQuarantinedException extends DomainException {

    public ShipmentQuarantinedException(ShipmentId shipmentId, String operation) {
        super(String.format(
            "Shipment %s is currently QUARANTINED due to an active excursion/incident. Operation '%s' is blocked until cleared by authorized QA.",
            shipmentId, operation
        ));
    }
}
