package com.coldchainos.shipment.infrastructure.persistence;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.Shipment;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.ShipmentRepository;
import com.coldchainos.shipment.domain.TrackingNumber;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Hexagonal Outbound Persistence Adapter for Shipment Aggregate.
 * Bridges the pure Domain Port (ShipmentRepository) with PostgreSQL / Spring Data JPA.
 */
@Repository
@RequiredArgsConstructor
public class PostgresShipmentRepositoryAdapter implements ShipmentRepository {

    private final SpringDataShipmentRepository springDataRepository;

    @Override
    @Transactional
    public void save(Shipment shipment) {
        ShipmentJpaEntity entity = ShipmentEntityMapper.toEntity(shipment);
        springDataRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findById(ShipmentId id) {
        return springDataRepository.findByIdWithDetails(id.value())
            .map(ShipmentEntityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Shipment> findByTrackingNumber(TenantId tenantId, TrackingNumber trackingNumber) {
        return springDataRepository.findByTenantAndTrackingWithDetails(tenantId.value(), trackingNumber.value())
            .map(ShipmentEntityMapper::toDomain);
    }
}
