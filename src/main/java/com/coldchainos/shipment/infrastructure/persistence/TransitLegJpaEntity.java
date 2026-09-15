package com.coldchainos.shipment.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transit_legs")
@Getter
@Setter
@NoArgsConstructor
public class TransitLegJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private ShipmentJpaEntity shipment;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "origin_code", nullable = false, length = 16)
    private String originCode;

    @Column(name = "origin_name", nullable = false, length = 128)
    private String originName;

    @Column(name = "origin_city", length = 64)
    private String originCity;

    @Column(name = "origin_country", nullable = false, length = 2)
    private String originCountry;

    @Column(name = "destination_code", nullable = false, length = 16)
    private String destinationCode;

    @Column(name = "destination_name", nullable = false, length = 128)
    private String destinationName;

    @Column(name = "destination_city", length = 64)
    private String destinationCity;

    @Column(name = "destination_country", nullable = false, length = 2)
    private String destinationCountry;

    @Column(name = "assigned_carrier_id", length = 64)
    private String assignedCarrierId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "estimated_departure")
    private Instant estimatedDeparture;

    @Column(name = "estimated_arrival")
    private Instant estimatedArrival;
}
