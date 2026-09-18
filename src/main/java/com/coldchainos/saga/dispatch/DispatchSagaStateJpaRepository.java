package com.coldchainos.saga.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DispatchSagaStateJpaRepository extends JpaRepository<DispatchSagaStateJpaEntity, UUID> {
    Optional<DispatchSagaStateJpaEntity> findByShipmentId(UUID shipmentId);
}
