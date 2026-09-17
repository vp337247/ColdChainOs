package com.coldchainos.shipment.domain.events;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.CarrierId;
import com.coldchainos.shipment.domain.LegId;
import com.coldchainos.shipment.domain.ShipmentId;

import java.time.Instant;
import java.util.UUID;

public record CarrierAssignedEvent(
    UUID eventId,
    Instant occurredAt,
    TenantId tenantId,
    ShipmentId shipmentId,
    LegId legId,
    CarrierId carrierId
) implements ShipmentEvent {

    public CarrierAssignedEvent(TenantId tenantId, ShipmentId shipmentId, LegId legId, CarrierId carrierId) {
        this(UUID.randomUUID(), Instant.now(), tenantId, shipmentId, legId, carrierId);
    }
}
