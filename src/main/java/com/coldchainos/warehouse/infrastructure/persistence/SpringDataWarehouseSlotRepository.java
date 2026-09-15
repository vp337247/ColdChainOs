package com.coldchainos.warehouse.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataWarehouseSlotRepository extends JpaRepository<WarehouseSlotJpaEntity, UUID> {

    @Query("SELECT s FROM WarehouseSlotJpaEntity s LEFT JOIN FETCH s.reservations WHERE s.id = :id")
    Optional<WarehouseSlotJpaEntity> findByIdWithDetails(@Param("id") UUID id);

    /**
     * Acquires an exclusive row-level lock (SELECT ... FOR UPDATE) in PostgreSQL.
     * Prevents any other concurrent transaction from reading or writing this slot until the current transaction commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM WarehouseSlotJpaEntity s WHERE s.id = :id")
    Optional<WarehouseSlotJpaEntity> findByIdWithPessimisticLock(@Param("id") UUID id);
}
