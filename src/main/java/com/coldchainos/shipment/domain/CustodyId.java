package com.coldchainos.shipment.domain;

import java.util.Objects;
import java.util.UUID;

public record CustodyId(UUID value) {
    public CustodyId {
        Objects.requireNonNull(value, "CustodyId cannot be null");
    }

    public static CustodyId newId() {
        return new CustodyId(UUID.randomUUID());
    }

    public static CustodyId of(String uuidString) {
        return new CustodyId(UUID.fromString(uuidString));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
