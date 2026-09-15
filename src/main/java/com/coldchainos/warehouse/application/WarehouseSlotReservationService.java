package com.coldchainos.warehouse.application;

import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.warehouse.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application Service for managing warehouse capacity and slot bookings.
 * Encapsulates transaction boundaries for concurrency control.
 */
@Service
@RequiredArgsConstructor
public class WarehouseSlotReservationService {

    private final WarehouseSlotRepository slotRepository;

    /**
     * Reserves a slot using PESSIMISTIC_WRITE locking (SELECT ... FOR UPDATE in PostgreSQL).
     * Serializes concurrent requests attempting to reserve the exact same slot.
     */
    @Transactional
    public SlotReservation reserveWithPessimisticLock(SlotId slotId, ShipmentId shipmentId, TimeWindow window) {
        WarehouseSlot slot = slotRepository.findByIdWithPessimisticLock(slotId)
            .orElseThrow(() -> new IllegalArgumentException("Slot not found: " + slotId));

        SlotReservation reservation = slot.reserve(shipmentId, window);
        slotRepository.save(slot);
        return reservation;
    }

    /**
     * Reserves a slot relying on JPA @Version Optimistic Locking.
     * Concurrent modifications will trigger ObjectOptimisticLockingFailureException at transaction commit.
     */
    @Transactional
    public SlotReservation reserveWithOptimisticLock(SlotId slotId, ShipmentId shipmentId, TimeWindow window) {
        WarehouseSlot slot = slotRepository.findById(slotId)
            .orElseThrow(() -> new IllegalArgumentException("Slot not found: " + slotId));

        SlotReservation reservation = slot.reserve(shipmentId, window);
        slotRepository.save(slot);
        return reservation;
    }
}
