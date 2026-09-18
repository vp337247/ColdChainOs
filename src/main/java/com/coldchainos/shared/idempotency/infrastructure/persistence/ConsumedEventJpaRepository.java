package com.coldchainos.shared.idempotency.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsumedEventJpaRepository extends JpaRepository<ConsumedEventJpaEntity, UUID> {

    boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);

    Optional<ConsumedEventJpaEntity> findByConsumerNameAndEventId(String consumerName, UUID eventId);

    List<ConsumedEventJpaEntity> findByConsumerName(String consumerName);

    List<ConsumedEventJpaEntity> findByAggregateId(String aggregateId);
}
