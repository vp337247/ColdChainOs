package com.coldchainos.shipment.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipment_telemetry_history")
@Getter
@Setter
@NoArgsConstructor
public class TelemetryHistoryJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "sensor_id", nullable = false, length = 64)
    private String sensorId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "temperature_celsius", nullable = false, precision = 5, scale = 2)
    private BigDecimal temperatureCelsius;

    @Column(name = "humidity_percentage", precision = 5, scale = 2)
    private BigDecimal humidityPercentage;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "battery_level", precision = 5, scale = 2)
    private BigDecimal batteryLevel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public TelemetryHistoryJpaEntity(UUID id, UUID shipmentId, String sensorId, Instant recordedAt,
                                     BigDecimal temperatureCelsius, BigDecimal humidityPercentage,
                                     BigDecimal latitude, BigDecimal longitude, BigDecimal batteryLevel,
                                     Instant createdAt) {
        this.id = id;
        this.shipmentId = shipmentId;
        this.sensorId = sensorId;
        this.recordedAt = recordedAt;
        this.temperatureCelsius = temperatureCelsius;
        this.humidityPercentage = humidityPercentage;
        this.latitude = latitude;
        this.longitude = longitude;
        this.batteryLevel = batteryLevel;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }
}
