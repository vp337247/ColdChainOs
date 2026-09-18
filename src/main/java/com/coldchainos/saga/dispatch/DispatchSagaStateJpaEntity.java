package com.coldchainos.saga.dispatch;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable state entity recording the execution progress and compensation journal of a dispatch saga.
 */
@Entity
@Table(name = "dispatch_saga_state")
@Getter
@Setter
@NoArgsConstructor
public class DispatchSagaStateJpaEntity {

    @Id
    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 64)
    private DispatchSagaStatus status;

    @Column(name = "current_step", nullable = false, length = 64)
    private String currentStep;

    @Column(name = "warehouse_slot_id")
    private UUID warehouseSlotId;

    @Column(name = "carrier_booking_id", length = 128)
    private String carrierBookingId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DispatchSagaStateJpaEntity(UUID sagaId, UUID shipmentId, DispatchSagaStatus status, String currentStep) {
        this.sagaId = sagaId;
        this.shipmentId = shipmentId;
        this.status = status;
        this.currentStep = currentStep;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
