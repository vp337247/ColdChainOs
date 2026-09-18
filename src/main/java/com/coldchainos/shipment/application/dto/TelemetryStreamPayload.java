package com.coldchainos.shipment.application.dto;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.GeoCoordinates;
import com.coldchainos.shipment.domain.SensorId;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.TelemetryReading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire-format DTO for transmitting high-throughput IoT sensor frames over Kafka.
 */
public record TelemetryStreamPayload(
    String tenantId,
    UUID shipmentId,
    String sensorId,
    Instant recordedAt,
    BigDecimal temperatureCelsius,
    BigDecimal humidityPercentage,
    BigDecimal latitude,
    BigDecimal longitude,
    BigDecimal batteryLevel
) {
    public TelemetryReading toDomainReading() {
        GeoCoordinates coordinates = (latitude != null && longitude != null)
            ? new GeoCoordinates(latitude, longitude)
            : null;
        return TelemetryReading.of(
            new SensorId(sensorId),
            recordedAt,
            temperatureCelsius,
            humidityPercentage,
            coordinates,
            batteryLevel
        );
    }

    public TenantId toTenantId() {
        return new TenantId(tenantId);
    }

    public ShipmentId toShipmentId() {
        return new ShipmentId(shipmentId);
    }
}
