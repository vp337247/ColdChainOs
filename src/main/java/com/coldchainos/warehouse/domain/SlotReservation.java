package com.coldchainos.warehouse.domain;

import com.coldchainos.shipment.domain.ShipmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity representing an active or historic slot booking.
 */
public class SlotReservation {

    private final UUID id;
    private final ShipmentId shipmentId;
    private final TimeWindow timeWindow;
    private SlotReservationStatus status;
    private final Instant createdAt;

    public SlotReservation(UUID id, ShipmentId shipmentId, TimeWindow timeWindow, SlotReservationStatus status, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "Reservation ID cannot be null");
        this.shipmentId = Objects.requireNonNull(shipmentId, "ShipmentId cannot be null");
        this.timeWindow = Objects.requireNonNull(timeWindow, "TimeWindow cannot be null");
        this.status = Objects.requireNonNull(status, "Status cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt cannot be null");
    }

    public static SlotReservation create(ShipmentId shipmentId, TimeWindow timeWindow) {
        return new SlotReservation(UUID.randomUUID(), shipmentId, timeWindow, SlotReservationStatus.ACTIVE, Instant.now());
    }

    public boolean isActive() {
        return this.status == SlotReservationStatus.ACTIVE;
    }

    public void cancel() {
        this.status = SlotReservationStatus.CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public ShipmentId getShipmentId() {
        return shipmentId;
    }

    public TimeWindow getTimeWindow() {
        return timeWindow;
    }

    public SlotReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
