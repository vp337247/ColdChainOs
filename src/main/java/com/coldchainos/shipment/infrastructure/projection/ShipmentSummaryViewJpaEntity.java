package com.coldchainos.shipment.infrastructure.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Materialized CQRS read-projection entity.
 * Flattened and de-normalized for high-speed dashboard searches and analytical filtering.
 */
@Entity
@Table(name = "shipment_summary_view")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentSummaryViewJpaEntity {

    @Id
    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "tracking_number", nullable = false, length = 32)
    private String trackingNumber;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "thermal_category", nullable = false, length = 32)
    private String thermalCategory;

    @Column(name = "origin_code", nullable = false, length = 16)
    private String originCode;

    @Column(name = "origin_city", length = 64)
    private String originCity;

    @Column(name = "destination_code", nullable = false, length = 16)
    private String destinationCode;

    @Column(name = "destination_city", length = 64)
    private String destinationCity;

    @Column(name = "assigned_carrier_id", length = 64)
    private String assignedCarrierId;

    @Column(name = "min_temperature", nullable = false, precision = 5, scale = 2)
    private BigDecimal minTemperature;

    @Column(name = "max_temperature", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxTemperature;

    @Column(name = "total_transit_legs", nullable = false)
    private Integer totalTransitLegs;

    @Column(name = "is_quarantined", nullable = false)
    private Boolean isQuarantined;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_event_type", length = 128)
    private String lastEventType;

    @Column(name = "last_event_timestamp")
    private Instant lastEventTimestamp;

    @Column(name = "projection_updated_at", nullable = false)
    private Instant projectionUpdatedAt;

    public ShipmentSummaryViewJpaEntity(
            UUID shipmentId,
            String tenantId,
            String trackingNumber,
            String status,
            String thermalCategory,
            String originCode,
            String originCity,
            String destinationCode,
            String destinationCity,
            String assignedCarrierId,
            BigDecimal minTemperature,
            BigDecimal maxTemperature,
            Integer totalTransitLegs,
            Boolean isQuarantined,
            Instant createdAt,
            Instant deliveredAt,
            String lastEventType,
            Instant lastEventTimestamp,
            Instant projectionUpdatedAt) {
        this.shipmentId = shipmentId;
        this.tenantId = tenantId;
        this.trackingNumber = trackingNumber;
        this.status = status;
        this.thermalCategory = thermalCategory;
        this.originCode = originCode;
        this.originCity = originCity;
        this.destinationCode = destinationCode;
        this.destinationCity = destinationCity;
        this.assignedCarrierId = assignedCarrierId;
        this.minTemperature = minTemperature;
        this.maxTemperature = maxTemperature;
        this.totalTransitLegs = totalTransitLegs != null ? totalTransitLegs : 1;
        this.isQuarantined = isQuarantined != null ? isQuarantined : false;
        this.createdAt = createdAt;
        this.deliveredAt = deliveredAt;
        this.lastEventType = lastEventType;
        this.lastEventTimestamp = lastEventTimestamp;
        this.projectionUpdatedAt = projectionUpdatedAt != null ? projectionUpdatedAt : Instant.now();
    }
}
