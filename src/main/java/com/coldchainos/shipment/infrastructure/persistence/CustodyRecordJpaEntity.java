package com.coldchainos.shipment.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "custody_records")
@Getter
@Setter
@NoArgsConstructor
public class CustodyRecordJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private ShipmentJpaEntity shipment;

    @Column(name = "releasing_party", nullable = false, length = 128)
    private String releasingParty;

    @Column(name = "receiving_party", nullable = false, length = 128)
    private String receivingParty;

    @Column(name = "surface_temperature", nullable = false, precision = 5, scale = 2)
    private BigDecimal surfaceTemperature;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "digital_signature", nullable = false, columnDefinition = "TEXT")
    private String digitalSignature;
}
