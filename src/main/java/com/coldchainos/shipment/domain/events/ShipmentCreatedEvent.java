package com.coldchainos.shipment.domain.events;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.TrackingNumber;
import com.coldchainos.shipment.domain.TemperatureThreshold;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ShipmentCreatedEvent(
    UUID eventId,
    Instant occurredAt,
    TenantId tenantId,
    ShipmentId shipmentId,
    TrackingNumber trackingNumber,
    TemperatureThreshold threshold
) implements ShipmentEvent {

    public ShipmentCreatedEvent(TenantId tenantId, ShipmentId shipmentId, TrackingNumber trackingNumber, TemperatureThreshold threshold) {
        this(UUID.randomUUID(), Instant.now(), tenantId, shipmentId, trackingNumber, threshold);
    }
}
