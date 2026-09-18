package com.coldchainos.shipment.infrastructure.projection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShipmentSummaryViewJpaRepository extends JpaRepository<ShipmentSummaryViewJpaEntity, UUID> {

    Optional<ShipmentSummaryViewJpaEntity> findByTrackingNumber(String trackingNumber);

    List<ShipmentSummaryViewJpaEntity> findByStatus(String status);

    List<ShipmentSummaryViewJpaEntity> findByAssignedCarrierId(String carrierId);

    List<ShipmentSummaryViewJpaEntity> findByIsQuarantinedTrue();

    List<ShipmentSummaryViewJpaEntity> findByThermalCategory(String thermalCategory);
}
