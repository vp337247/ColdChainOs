package com.coldchainos.shipment.domain.events;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.ShipmentId;

import java.time.Instant;
import java.util.UUID;

public record ShipmentQuarantinedEvent(
    UUID eventId,
    Instant occurredAt,
    TenantId tenantId,
    ShipmentId shipmentId,
    UUID incidentId,
    String reason
) implements DomainEvent {

    public ShipmentQuarantinedEvent(TenantId tenantId, ShipmentId shipmentId, UUID incidentId, String reason) {
        this(UUID.randomUUID(), Instant.now(), tenantId, shipmentId, incidentId, reason);
    }
}
