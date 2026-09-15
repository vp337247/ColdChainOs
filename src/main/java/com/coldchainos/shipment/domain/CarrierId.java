package com.coldchainos.shipment.domain;

import java.util.Objects;

public record CarrierId(String value) {
    public CarrierId {
        Objects.requireNonNull(value, "CarrierId cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("CarrierId cannot be blank");
        }
        value = trimmed;
    }

    public static CarrierId of(String value) {
        return new CarrierId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
