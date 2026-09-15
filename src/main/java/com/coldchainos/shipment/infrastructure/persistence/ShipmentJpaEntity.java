package com.coldchainos.shipment.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "shipments")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "tracking_number", nullable = false, length = 32)
    private String trackingNumber;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "pre_quarantine_status", length = 32)
    private String preQuarantineStatus;

    @Column(name = "min_temperature", nullable = false, precision = 5, scale = 2)
    private BigDecimal minTemperature;

    @Column(name = "max_temperature", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxTemperature;

    @Column(name = "max_excursion_seconds", nullable = false)
    private Long maxExcursionSeconds;

    @Column(name = "thermal_category", nullable = false, length = 32)
    private String thermalCategory;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "proof_of_delivery_signature")
    private String proofOfDeliverySignature;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sequenceNumber ASC")
    private java.util.Set<TransitLegJpaEntity> legs = new java.util.LinkedHashSet<>();

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("recordedAt ASC")
    private java.util.Set<CustodyRecordJpaEntity> custodyRecords = new java.util.LinkedHashSet<>();

    public void addLeg(TransitLegJpaEntity leg) {
        legs.add(leg);
        leg.setShipment(this);
    }

    public void addCustodyRecord(CustodyRecordJpaEntity record) {
        custodyRecords.add(record);
        record.setShipment(this);
    }
}
