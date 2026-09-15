package com.coldchainos.shipment.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Human-readable, globally unique tracking identifier (e.g. SHP-2026-A1B2C3).
 */
public record TrackingNumber(String value) {

    private static final Pattern VALID_TRACKING = Pattern.compile("^SHP-\\d{4}-[A-Z0-9]{6,12}$");

    public TrackingNumber {
        Objects.requireNonNull(value, "TrackingNumber cannot be null");
        String normalized = value.trim().toUpperCase();
        if (!VALID_TRACKING.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "Tracking number must match format SHP-YYYY-XXXXXX (e.g., SHP-2026-X8Y7Z6): " + value
            );
        }
        value = normalized;
    }

    public static TrackingNumber of(String value) {
        return new TrackingNumber(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
