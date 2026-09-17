package com.coldchainos.shipment.domain.events;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.ShipmentStatus;

import java.time.Instant;
import java.util.UUID;

public record ShipmentQuarantineReleasedEvent(
    UUID eventId,
    Instant occurredAt,
    TenantId tenantId,
    ShipmentId shipmentId,
    ShipmentStatus restoredStatus,
    String qaOfficerUserId,
    String justification,
    String digitalSignature
) implements ShipmentEvent {

    public ShipmentQuarantineReleasedEvent(
        TenantId tenantId,
        ShipmentId shipmentId,
        ShipmentStatus restoredStatus,
        String qaOfficerUserId,
        String justification,
        String digitalSignature
    ) {
        this(UUID.randomUUID(), Instant.now(), tenantId, shipmentId, restoredStatus, qaOfficerUserId, justification, digitalSignature);
    }
}
