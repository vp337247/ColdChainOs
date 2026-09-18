package com.coldchainos.shipment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TelemetryHistoryJpaRepository extends JpaRepository<TelemetryHistoryJpaEntity, UUID> {

    List<TelemetryHistoryJpaEntity> findByShipmentIdOrderByRecordedAtDesc(UUID shipmentId);

    List<TelemetryHistoryJpaEntity> findTop50ByShipmentIdOrderByRecordedAtDesc(UUID shipmentId);
}
