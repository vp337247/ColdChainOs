package com.coldchainos.shipment.domain;

import java.util.Objects;

/**
 * Strongly typed value object representing an IoT tracking beacon / sensor device identifier.
 */
public record SensorId(String value) {

    public SensorId {
        Objects.requireNonNull(value, "SensorId cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new IllegalArgumentException("SensorId must be between 1 and 64 characters: " + value);
        }
        value = trimmed;
    }

    public static SensorId of(String value) {
        return new SensorId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
