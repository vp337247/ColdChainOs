package com.coldchainos.shipment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value Object defining the strict environmental stability envelope of a shipment payload.
 *
 * Invariants:
 * 1. minCelsius <= maxCelsius
 * 2. maxExcursionSeconds >= 0
 */
public record TemperatureThreshold(
    BigDecimal minCelsius,
    BigDecimal maxCelsius,
    long maxExcursionSeconds,
    ThermalCategory category
) {

    public TemperatureThreshold {
        Objects.requireNonNull(minCelsius, "minCelsius cannot be null");
        Objects.requireNonNull(maxCelsius, "maxCelsius cannot be null");
        Objects.requireNonNull(category, "category cannot be null");

        if (minCelsius.compareTo(maxCelsius) > 0) {
            throw new IllegalArgumentException(
                String.format("minCelsius (%.2f) cannot be greater than maxCelsius (%.2f)", minCelsius, maxCelsius)
            );
        }

        if (maxExcursionSeconds < 0) {
            throw new IllegalArgumentException("maxExcursionSeconds cannot be negative: " + maxExcursionSeconds);
        }
    }

    public static TemperatureThreshold of(double min, double max, long maxExcursionSeconds, ThermalCategory category) {
        return new TemperatureThreshold(
            BigDecimal.valueOf(min).setScale(2, RoundingMode.HALF_UP),
            BigDecimal.valueOf(max).setScale(2, RoundingMode.HALF_UP),
            maxExcursionSeconds,
            category
        );
    }

    public static TemperatureThreshold forCategory(ThermalCategory category, long maxExcursionSeconds) {
        return of(category.getDefaultMinCelsius(), category.getDefaultMaxCelsius(), maxExcursionSeconds, category);
    }

    /**
     * Checks if a temperature reading breaches the configured thresholds.
     */
    public boolean isExcursion(BigDecimal reading) {
        Objects.requireNonNull(reading, "reading cannot be null");
        return reading.compareTo(minCelsius) < 0 || reading.compareTo(maxCelsius) > 0;
    }

    public boolean isExcursion(double reading) {
        return isExcursion(BigDecimal.valueOf(reading).setScale(2, RoundingMode.HALF_UP));
    }
}
