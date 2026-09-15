package com.coldchainos.shipment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataShipmentRepository extends JpaRepository<ShipmentJpaEntity, UUID> {

    @Query("SELECT s FROM ShipmentJpaEntity s LEFT JOIN FETCH s.legs LEFT JOIN FETCH s.custodyRecords WHERE s.id = :id")
    Optional<ShipmentJpaEntity> findByIdWithDetails(@Param("id") UUID id);

    @Query("SELECT s FROM ShipmentJpaEntity s LEFT JOIN FETCH s.legs LEFT JOIN FETCH s.custodyRecords WHERE s.tenantId = :tenantId AND s.trackingNumber = :trackingNumber")
    Optional<ShipmentJpaEntity> findByTenantAndTrackingWithDetails(@Param("tenantId") String tenantId, @Param("trackingNumber") String trackingNumber);
}
