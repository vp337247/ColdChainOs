package com.coldchainos.shared.outbox.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SpringDataOutboxEventRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    List<OutboxEventJpaEntity> findTop50ByStatusOrderByOccurredAtAsc(OutboxStatus status);
}
