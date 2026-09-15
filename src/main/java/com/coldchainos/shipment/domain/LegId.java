package com.coldchainos.shipment.domain;

import java.util.Objects;
import java.util.UUID;

public record LegId(UUID value) {
    public LegId {
        Objects.requireNonNull(value, "LegId cannot be null");
    }

    public static LegId newId() {
        return new LegId(UUID.randomUUID());
    }

    public static LegId of(String uuidString) {
        return new LegId(UUID.fromString(uuidString));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
