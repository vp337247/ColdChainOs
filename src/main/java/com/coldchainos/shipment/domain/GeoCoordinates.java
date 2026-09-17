package com.coldchainos.shipment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value object representing Geographic GPS Coordinates (Latitude, Longitude).
 */
public record GeoCoordinates(BigDecimal latitude, BigDecimal longitude) {

    public GeoCoordinates {
        Objects.requireNonNull(latitude, "latitude must not be null");
        Objects.requireNonNull(longitude, "longitude must not be null");

        if (latitude.compareTo(BigDecimal.valueOf(-90.0)) < 0 || latitude.compareTo(BigDecimal.valueOf(90.0)) > 0) {
            throw new IllegalArgumentException("Latitude must be between -90 and +90 degrees: " + latitude);
        }
        if (longitude.compareTo(BigDecimal.valueOf(-180.0)) < 0 || longitude.compareTo(BigDecimal.valueOf(180.0)) > 0) {
            throw new IllegalArgumentException("Longitude must be between -180 and +180 degrees: " + longitude);
        }

        latitude = latitude.setScale(6, RoundingMode.HALF_UP);
        longitude = longitude.setScale(6, RoundingMode.HALF_UP);
    }

    public static GeoCoordinates of(double latitude, double longitude) {
        return new GeoCoordinates(BigDecimal.valueOf(latitude), BigDecimal.valueOf(longitude));
    }

    public static GeoCoordinates of(BigDecimal latitude, BigDecimal longitude) {
        return new GeoCoordinates(latitude, longitude);
    }
}
