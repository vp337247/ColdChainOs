package com.coldchainos.warehouse.domain;

import java.util.Optional;

/**
 * Domain Port for WarehouseSlot persistence and concurrency locking.
 */
public interface WarehouseSlotRepository {

    WarehouseSlot save(WarehouseSlot slot);

    Optional<WarehouseSlot> findById(SlotId id);

    /**
     * Retrieves the WarehouseSlot with a database-level PESSIMISTIC_WRITE lock (SELECT ... FOR UPDATE).
     * Guarantees that concurrent transactions attempting to read this slot are serialized.
     */
    Optional<WarehouseSlot> findByIdWithPessimisticLock(SlotId id);
}
