package com.coldchainos.shipment.domain.events;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.ShipmentId;

import java.time.Instant;
import java.util.UUID;

public record ShipmentDeliveredEvent(
    UUID eventId,
    Instant occurredAt,
    TenantId tenantId,
    ShipmentId shipmentId,
    String proofOfDeliverySignature
) implements DomainEvent {

    public ShipmentDeliveredEvent(TenantId tenantId, ShipmentId shipmentId, String proofOfDeliverySignature) {
        this(UUID.randomUUID(), Instant.now(), tenantId, shipmentId, proofOfDeliverySignature);
    }
}
