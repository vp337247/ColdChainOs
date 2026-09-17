package com.coldchainos.shipment.domain.events;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.ShipmentId;

/**
 * Common contract for all Shipment bounded context domain events.
 */
public interface ShipmentEvent extends DomainEvent {

    TenantId tenantId();

    ShipmentId shipmentId();

    @Override
    default String aggregateType() {
        return "SHIPMENT";
    }

    @Override
    default String aggregateId() {
        return shipmentId() != null ? shipmentId().value().toString() : "";
    }
}
