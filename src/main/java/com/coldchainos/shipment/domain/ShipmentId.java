package com.coldchainos.shipment.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for a Shipment Aggregate Root.
 */
public record ShipmentId(UUID value) {

    public ShipmentId {
        Objects.requireNonNull(value, "ShipmentId cannot be null");
    }

    public static ShipmentId newId() {
        return new ShipmentId(UUID.randomUUID());
    }

    public static ShipmentId of(String uuidString) {
        return new ShipmentId(UUID.fromString(uuidString));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
