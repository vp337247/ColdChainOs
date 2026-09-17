package com.coldchainos.shipment.domain.events;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.ShipmentId;

import java.time.Instant;
import java.util.UUID;

public record ShipmentCancelledEvent(
    UUID eventId,
    Instant occurredAt,
    TenantId tenantId,
    ShipmentId shipmentId,
    String reason
) implements ShipmentEvent {

    public ShipmentCancelledEvent(TenantId tenantId, ShipmentId shipmentId, String reason) {
        this(UUID.randomUUID(), Instant.now(), tenantId, shipmentId, reason);
    }
}
