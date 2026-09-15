package com.coldchainos.shipment.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Entity representing an individual leg of a multi-leg shipment journey.
 */
public class TransitLeg {

    private final LegId id;
    private final int sequenceNumber;
    private final Location origin;
    private final Location destination;
    private CarrierId assignedCarrierId;
    private LegStatus status;
    private Instant estimatedDeparture;
    private Instant estimatedArrival;

    public TransitLeg(
        LegId id,
        int sequenceNumber,
        Location origin,
        Location destination,
        Instant estimatedDeparture,
        Instant estimatedArrival
    ) {
        this.id = Objects.requireNonNull(id, "LegId cannot be null");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        this.sequenceNumber = sequenceNumber;
        this.origin = Objects.requireNonNull(origin, "origin cannot be null");
        this.destination = Objects.requireNonNull(destination, "destination cannot be null");
        this.status = LegStatus.PENDING;
        this.estimatedDeparture = estimatedDeparture;
        this.estimatedArrival = estimatedArrival;
    }

    public static TransitLeg of(
        int sequenceNumber,
        Location origin,
        Location destination,
        Instant estimatedDeparture,
        Instant estimatedArrival
    ) {
        return new TransitLeg(LegId.newId(), sequenceNumber, origin, destination, estimatedDeparture, estimatedArrival);
    }

    public void assignCarrier(CarrierId carrierId) {
        Objects.requireNonNull(carrierId, "carrierId cannot be null");
        if (this.status == LegStatus.COMPLETED || this.status == LegStatus.CANCELLED) {
            throw new IllegalStateException("Cannot assign carrier to leg in status: " + this.status);
        }
        this.assignedCarrierId = carrierId;
        this.status = LegStatus.ASSIGNED;
    }

    public void start() {
        if (this.assignedCarrierId == null) {
            throw new IllegalStateException("Cannot start transit leg without an assigned carrier");
        }
        this.status = LegStatus.IN_PROGRESS;
    }

    public void complete() {
        if (this.status != LegStatus.IN_PROGRESS && this.status != LegStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot complete leg in status: " + this.status);
        }
        this.status = LegStatus.COMPLETED;
    }

    public LegId getId() {
        return id;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public Location getOrigin() {
        return origin;
    }

    public Location getDestination() {
        return destination;
    }

    public CarrierId getAssignedCarrierId() {
        return assignedCarrierId;
    }

    public LegStatus getStatus() {
        return status;
    }

    public Instant getEstimatedDeparture() {
        return estimatedDeparture;
    }

    public Instant getEstimatedArrival() {
        return estimatedArrival;
    }
}
