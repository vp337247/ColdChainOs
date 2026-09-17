package com.coldchainos.shipment.domain.events;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.CustodyId;
import com.coldchainos.shipment.domain.ShipmentId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustodyTransferredEvent(
    UUID eventId,
    Instant occurredAt,
    TenantId tenantId,
    ShipmentId shipmentId,
    CustodyId custodyId,
    String releasingParty,
    String receivingParty,
    BigDecimal surfaceTemperatureCelsius
) implements ShipmentEvent {

    public CustodyTransferredEvent(
        TenantId tenantId,
        ShipmentId shipmentId,
        CustodyId custodyId,
        String releasingParty,
        String receivingParty,
        BigDecimal surfaceTemperatureCelsius
    ) {
        this(UUID.randomUUID(), Instant.now(), tenantId, shipmentId, custodyId, releasingParty, receivingParty, surfaceTemperatureCelsius);
    }
}
