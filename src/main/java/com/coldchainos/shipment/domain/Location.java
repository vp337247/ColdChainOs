package com.coldchainos.shipment.domain;

import java.util.Objects;

/**
 * Value object representing a waypoint or facility location.
 */
public record Location(
    String code,
    String name,
    String city,
    String countryCode
) {

    public Location {
        Objects.requireNonNull(code, "code cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(countryCode, "countryCode cannot be null");
        if (countryCode.trim().length() != 2) {
            throw new IllegalArgumentException("countryCode must be ISO 2-letter format: " + countryCode);
        }
    }

    public static Location of(String code, String name, String city, String countryCode) {
        return new Location(code.trim().toUpperCase(), name.trim(), city != null ? city.trim() : "", countryCode.trim().toUpperCase());
    }
}
