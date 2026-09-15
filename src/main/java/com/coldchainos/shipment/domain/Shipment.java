package com.coldchainos.shipment.domain;

import com.coldchainos.shared.domain.AggregateRoot;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.events.*;
import com.coldchainos.shipment.domain.exceptions.InvalidStateTransitionException;
import com.coldchainos.shipment.domain.exceptions.ShipmentQuarantinedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * The Shipment Aggregate Root.
 *
 * Enforces all lifecycle invariants, custody chain validation,
 * deterministic state transitions, and quarantine locking.
 */
public class Shipment extends AggregateRoot<ShipmentId> {

    private final ShipmentId id;
    private final TenantId tenantId;
    private final TrackingNumber trackingNumber;
    private final TemperatureThreshold threshold;
    private final List<TransitLeg> legs = new ArrayList<>();
    private final List<CustodyRecord> custodyHistory = new ArrayList<>();
    private final Instant createdAt;

    private ShipmentStatus status;
    private ShipmentStatus preQuarantineStatus;
    private Instant deliveredAt;
    private String proofOfDeliverySignature;

    private Shipment(
        ShipmentId id,
        TenantId tenantId,
        TrackingNumber trackingNumber,
        TemperatureThreshold threshold,
        List<TransitLeg> initialLegs
    ) {
        this.id = Objects.requireNonNull(id, "ShipmentId cannot be null");
        this.tenantId = Objects.requireNonNull(tenantId, "TenantId cannot be null");
        this.trackingNumber = Objects.requireNonNull(trackingNumber, "TrackingNumber cannot be null");
        this.threshold = Objects.requireNonNull(threshold, "TemperatureThreshold cannot be null");

        if (initialLegs == null || initialLegs.isEmpty()) {
            throw new IllegalArgumentException("A shipment must have at least one transit leg");
        }

        // Validate contiguous sequence numbers (1, 2, 3...)
        List<TransitLeg> sortedLegs = new ArrayList<>(initialLegs);
        sortedLegs.sort(Comparator.comparingInt(TransitLeg::getSequenceNumber));
        for (int i = 0; i < sortedLegs.size(); i++) {
            if (sortedLegs.get(i).getSequenceNumber() != i + 1) {
                throw new IllegalArgumentException("Transit legs must have contiguous sequence numbers starting at 1");
            }
        }
        this.legs.addAll(sortedLegs);

        this.status = ShipmentStatus.CREATED;
        this.createdAt = Instant.now();

        registerEvent(new ShipmentCreatedEvent(this.tenantId, this.id, this.trackingNumber, this.threshold));
    }

    public static Shipment create(
        TenantId tenantId,
        TrackingNumber trackingNumber,
        TemperatureThreshold threshold,
        List<TransitLeg> legs
    ) {
        return new Shipment(ShipmentId.newId(), tenantId, trackingNumber, threshold, legs);
    }

    public static Shipment createWithId(
        ShipmentId id,
        TenantId tenantId,
        TrackingNumber trackingNumber,
        TemperatureThreshold threshold,
        List<TransitLeg> legs
    ) {
        return new Shipment(id, tenantId, trackingNumber, threshold, legs);
    }

    /**
     * Assigns a carrier to a specific leg of the journey.
     * When all legs have assigned carriers, shipment automatically advances to ASSIGNED.
     */
    public void assignCarrier(LegId legId, CarrierId carrierId) {
        checkNotQuarantined("assignCarrier");

        if (this.status != ShipmentStatus.CREATED && this.status != ShipmentStatus.ASSIGNED) {
            throw new InvalidStateTransitionException(
                this.status,
                ShipmentStatus.ASSIGNED,
                "Carriers can only be assigned when shipment is CREATED or ASSIGNED"
            );
        }

        TransitLeg leg = findLegOrThrow(legId);
        leg.assignCarrier(carrierId);

        registerEvent(new CarrierAssignedEvent(this.tenantId, this.id, legId, carrierId));

        boolean allLegsAssigned = legs.stream().allMatch(l -> l.getAssignedCarrierId() != null);
        if (allLegsAssigned && this.status == ShipmentStatus.CREATED) {
            this.status = ShipmentStatus.ASSIGNED;
        }
    }

    /**
     * Starts transit on the first leg.
     */
    public void startTransit() {
        checkNotQuarantined("startTransit");

        if (this.status != ShipmentStatus.ASSIGNED) {
            throw new InvalidStateTransitionException(
                this.status,
                ShipmentStatus.IN_TRANSIT,
                "Shipment must be in ASSIGNED status before starting transit"
            );
        }

        TransitLeg firstLeg = legs.get(0);
        firstLeg.start();
        this.status = ShipmentStatus.IN_TRANSIT;
    }

    /**
     * Arrives at a designated port of entry / exit.
     */
    public void arriveAtPort() {
        checkNotQuarantined("arriveAtPort");

        if (this.status != ShipmentStatus.IN_TRANSIT) {
            throw new InvalidStateTransitionException(this.status, ShipmentStatus.AT_PORT);
        }

        this.status = ShipmentStatus.AT_PORT;
    }

    /**
     * Arrives at a cold storage or cross-dock warehouse facility.
     */
    public void arriveAtWarehouse() {
        checkNotQuarantined("arriveAtWarehouse");

        if (this.status != ShipmentStatus.IN_TRANSIT && this.status != ShipmentStatus.AT_PORT) {
            throw new InvalidStateTransitionException(this.status, ShipmentStatus.WAREHOUSE);
        }

        this.status = ShipmentStatus.WAREHOUSE;
    }

    /**
     * Resumes transit from port or warehouse.
     */
    public void resumeTransit(int nextLegSequence) {
        checkNotQuarantined("resumeTransit");

        if (this.status != ShipmentStatus.AT_PORT && this.status != ShipmentStatus.WAREHOUSE) {
            throw new InvalidStateTransitionException(this.status, ShipmentStatus.IN_TRANSIT);
        }

        TransitLeg nextLeg = legs.stream()
            .filter(l -> l.getSequenceNumber() == nextLegSequence)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No leg found with sequence: " + nextLegSequence));

        nextLeg.start();
        this.status = ShipmentStatus.IN_TRANSIT;
    }

    public void completeLeg(LegId legId) {
        checkNotQuarantined("completeLeg");
        TransitLeg leg = findLegOrThrow(legId);
        leg.complete();
    }

    /**
     * Records an immutable custody transfer between two handling parties.
     */
    public void recordCustodyHandoff(
        String releasingParty,
        String receivingParty,
        BigDecimal surfaceTemperatureCelsius,
        String digitalSignature
    ) {
        checkNotQuarantined("recordCustodyHandoff");

        if (this.status.isTerminal()) {
            throw new IllegalStateException("Cannot record custody on terminal shipment (status: " + this.status + ")");
        }

        CustodyRecord record = CustodyRecord.of(releasingParty, receivingParty, surfaceTemperatureCelsius, digitalSignature);
        this.custodyHistory.add(record);

        registerEvent(new CustodyTransferredEvent(
            this.tenantId,
            this.id,
            record.getId(),
            releasingParty,
            receivingParty,
            surfaceTemperatureCelsius
        ));
    }

    /**
     * Enforces immediate legal quarantine upon severe temperature excursion or physical breach.
     * Prevents all further movement or delivery until formally cleared.
     */
    public void applyQuarantine(UUID incidentId, String reason) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException("Cannot quarantine a completed or cancelled shipment");
        }

        if (this.status == ShipmentStatus.QUARANTINED) {
            return; // Idempotent quarantine
        }

        this.preQuarantineStatus = this.status;
        this.status = ShipmentStatus.QUARANTINED;

        registerEvent(new ShipmentQuarantinedEvent(this.tenantId, this.id, incidentId, reason));
    }

    /**
     * Releases quarantine after formal QA review in compliance with FDA 21 CFR Part 11.
     * Requires explicit QA user identity, justification, and digital signature.
     */
    public void releaseQuarantine(String qaOfficerUserId, String justification, String digitalSignature) {
        if (this.status != ShipmentStatus.QUARANTINED) {
            throw new IllegalStateException("Shipment is not in QUARANTINED status");
        }

        if (qaOfficerUserId == null || qaOfficerUserId.isBlank()) {
            throw new IllegalArgumentException("QA Officer user ID is required for 21 CFR Part 11 sign-off");
        }
        if (justification == null || justification.isBlank()) {
            throw new IllegalArgumentException("Investigation justification is mandatory to release quarantine");
        }
        if (digitalSignature == null || digitalSignature.isBlank()) {
            throw new IllegalArgumentException("Digital signature token is required");
        }

        ShipmentStatus restored = this.preQuarantineStatus != null ? this.preQuarantineStatus : ShipmentStatus.IN_TRANSIT;
        this.status = restored;
        this.preQuarantineStatus = null;

        registerEvent(new ShipmentQuarantineReleasedEvent(
            this.tenantId,
            this.id,
            restored,
            qaOfficerUserId,
            justification,
            digitalSignature
        ));
    }

    /**
     * Marks final delivery of shipment to customer / destination hospital.
     */
    public void markDelivered(String proofOfDeliverySignature) {
        checkNotQuarantined("markDelivered");

        if (this.status != ShipmentStatus.IN_TRANSIT && this.status != ShipmentStatus.WAREHOUSE) {
            throw new InvalidStateTransitionException(
                this.status,
                ShipmentStatus.DELIVERED,
                "Shipment must be in IN_TRANSIT or WAREHOUSE to be delivered"
            );
        }

        if (proofOfDeliverySignature == null || proofOfDeliverySignature.isBlank()) {
            throw new IllegalArgumentException("Proof of delivery signature is required");
        }

        // Complete all remaining legs
        for (TransitLeg leg : legs) {
            if (leg.getStatus() == LegStatus.IN_PROGRESS || leg.getStatus() == LegStatus.ASSIGNED) {
                leg.complete();
            }
        }

        this.status = ShipmentStatus.DELIVERED;
        this.deliveredAt = Instant.now();
        this.proofOfDeliverySignature = proofOfDeliverySignature;

        registerEvent(new ShipmentDeliveredEvent(this.tenantId, this.id, proofOfDeliverySignature));
    }

    /**
     * Cancels a shipment before transit has begun.
     */
    public void cancel(String reason) {
        if (this.status != ShipmentStatus.CREATED && this.status != ShipmentStatus.ASSIGNED) {
            throw new InvalidStateTransitionException(
                this.status,
                ShipmentStatus.CANCELLED,
                "Shipment can only be cancelled prior to departure (current: " + this.status + ")"
            );
        }

        this.status = ShipmentStatus.CANCELLED;
        registerEvent(new ShipmentCancelledEvent(this.tenantId, this.id, reason));
    }

    private void checkNotQuarantined(String operation) {
        if (this.status == ShipmentStatus.QUARANTINED) {
            throw new ShipmentQuarantinedException(this.id, operation);
        }
    }

    private TransitLeg findLegOrThrow(LegId legId) {
        return legs.stream()
            .filter(l -> l.getId().equals(legId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Leg not found: " + legId));
    }

    @Override
    public ShipmentId getId() {
        return id;
    }

    public TenantId getTenantId() {
        return tenantId;
    }

    public TrackingNumber getTrackingNumber() {
        return trackingNumber;
    }

    public TemperatureThreshold getThreshold() {
        return threshold;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public ShipmentStatus getPreQuarantineStatus() {
        return preQuarantineStatus;
    }

    public List<TransitLeg> getLegs() {
        return Collections.unmodifiableList(legs);
    }

    public List<CustodyRecord> getCustodyHistory() {
        return Collections.unmodifiableList(custodyHistory);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public String getProofOfDeliverySignature() {
        return proofOfDeliverySignature;
    }
}
