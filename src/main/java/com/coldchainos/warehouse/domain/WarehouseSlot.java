package com.coldchainos.warehouse.domain;

import com.coldchainos.shared.domain.AggregateRoot;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.ThermalCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * WarehouseSlot Aggregate Root.
 *
 * Enforces the physical and temporal capacity invariants:
 * One slot CANNOT be reserved by two concurrent requests for overlapping time windows.
 */
public class WarehouseSlot extends AggregateRoot<SlotId> {

    private final SlotId id;
    private final WarehouseId warehouseId;
    private final String slotCode;
    private final ThermalCategory thermalCategory;
    private final double maxCapacityKg;
    private final List<SlotReservation> reservations = new ArrayList<>();
    private Long version;

    public WarehouseSlot(
        SlotId id,
        WarehouseId warehouseId,
        String slotCode,
        ThermalCategory thermalCategory,
        double maxCapacityKg
    ) {
        this.id = Objects.requireNonNull(id, "SlotId cannot be null");
        this.warehouseId = Objects.requireNonNull(warehouseId, "WarehouseId cannot be null");
        this.slotCode = Objects.requireNonNull(slotCode, "slotCode cannot be null");
        this.thermalCategory = Objects.requireNonNull(thermalCategory, "thermalCategory cannot be null");
        if (maxCapacityKg <= 0) {
            throw new IllegalArgumentException("maxCapacityKg must be positive");
        }
        this.maxCapacityKg = maxCapacityKg;
        this.version = 0L;
    }

    public static WarehouseSlot create(
        WarehouseId warehouseId,
        String slotCode,
        ThermalCategory thermalCategory,
        double maxCapacityKg
    ) {
        return new WarehouseSlot(SlotId.newId(), warehouseId, slotCode, thermalCategory, maxCapacityKg);
    }

    public static WarehouseSlot reconstitute(
        SlotId id,
        WarehouseId warehouseId,
        String slotCode,
        ThermalCategory thermalCategory,
        double maxCapacityKg,
        List<SlotReservation> existingReservations,
        Long version
    ) {
        WarehouseSlot slot = new WarehouseSlot(id, warehouseId, slotCode, thermalCategory, maxCapacityKg);
        if (existingReservations != null) {
            slot.reservations.addAll(existingReservations);
        }
        slot.version = version;
        return slot;
    }

    /**
     * Atomically reserves this physical slot for an incoming shipment.
     * Enforces the business invariant that no overlapping active reservations can exist.
     */
    public SlotReservation reserve(ShipmentId shipmentId, TimeWindow window) {
        Objects.requireNonNull(shipmentId, "shipmentId cannot be null");
        Objects.requireNonNull(window, "TimeWindow cannot be null");

        for (SlotReservation existing : reservations) {
            if (existing.isActive() && existing.getTimeWindow().overlapsWith(window)) {
                throw new SlotReservationException(String.format(
                    "Concurrency conflict: Slot '%s' in Warehouse '%s' is already reserved for overlapping window [%s to %s]",
                    slotCode, warehouseId, existing.getTimeWindow().startTime(), existing.getTimeWindow().endTime()
                ));
            }
        }

        SlotReservation reservation = SlotReservation.create(shipmentId, window);
        this.reservations.add(reservation);
        return reservation;
    }

    /**
     * Compensating action: releases/cancels an active reservation for a given shipment.
     */
    public boolean releaseReservation(ShipmentId shipmentId) {
        Objects.requireNonNull(shipmentId, "shipmentId cannot be null");
        for (SlotReservation res : reservations) {
            if (res.getShipmentId().equals(shipmentId) && res.isActive()) {
                res.cancel();
                return true;
            }
        }
        return false;
    }

    @Override
    public SlotId getId() {
        return id;
    }

    public WarehouseId getWarehouseId() {
        return warehouseId;
    }

    public String getSlotCode() {
        return slotCode;
    }

    public ThermalCategory getThermalCategory() {
        return thermalCategory;
    }

    public double getMaxCapacityKg() {
        return maxCapacityKg;
    }

    public List<SlotReservation> getReservations() {
        return Collections.unmodifiableList(reservations);
    }

    public Long getVersion() {
        return version;
    }
}
