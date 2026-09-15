package com.coldchainos.shipment.domain;

import com.coldchainos.shared.domain.TenantId;

import java.util.Optional;

/**
 * Domain Port (Outbound) for Shipment persistence.
 * Pure interface living in the Domain layer, with zero framework dependencies.
 */
public interface ShipmentRepository {

    void save(Shipment shipment);

    Optional<Shipment> findById(ShipmentId id);

    Optional<Shipment> findByTrackingNumber(TenantId tenantId, TrackingNumber trackingNumber);
}
