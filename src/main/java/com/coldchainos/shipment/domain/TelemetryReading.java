package com.coldchainos.shipment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain value object representing a single telemetry reading from an IoT beacon.
 */
public record TelemetryReading(
    SensorId sensorId,
    Instant recordedAt,
    BigDecimal temperatureCelsius,
    BigDecimal humidityPercentage,
    GeoCoordinates coordinates,
    BigDecimal batteryLevelPercentage
) {

    public TelemetryReading {
        Objects.requireNonNull(sensorId, "sensorId must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        Objects.requireNonNull(temperatureCelsius, "temperatureCelsius must not be null");

        temperatureCelsius = temperatureCelsius.setScale(2, RoundingMode.HALF_UP);

        if (humidityPercentage != null) {
            if (humidityPercentage.compareTo(BigDecimal.ZERO) < 0 || humidityPercentage.compareTo(BigDecimal.valueOf(100.0)) > 0) {
                throw new IllegalArgumentException("Humidity percentage must be between 0 and 100: " + humidityPercentage);
            }
            humidityPercentage = humidityPercentage.setScale(2, RoundingMode.HALF_UP);
        }

        if (batteryLevelPercentage != null) {
            if (batteryLevelPercentage.compareTo(BigDecimal.ZERO) < 0 || batteryLevelPercentage.compareTo(BigDecimal.valueOf(100.0)) > 0) {
                throw new IllegalArgumentException("Battery level must be between 0 and 100: " + batteryLevelPercentage);
            }
            batteryLevelPercentage = batteryLevelPercentage.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static TelemetryReading of(
        SensorId sensorId,
        Instant recordedAt,
        BigDecimal temperatureCelsius,
        BigDecimal humidityPercentage,
        GeoCoordinates coordinates,
        BigDecimal batteryLevelPercentage
    ) {
        return new TelemetryReading(
            sensorId,
            recordedAt,
            temperatureCelsius,
            humidityPercentage,
            coordinates,
            batteryLevelPercentage
        );
    }
}
