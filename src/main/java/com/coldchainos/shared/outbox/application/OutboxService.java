package com.coldchainos.shared.outbox.application;

import com.coldchainos.shared.domain.DomainEvent;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxEventJpaEntity;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxStatus;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists domain events into the transactional outbox within the caller's active database transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final SpringDataOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveEvents(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        List<OutboxEventJpaEntity> entities = new ArrayList<>();
        for (DomainEvent event : events) {
            try {
                String payload = objectMapper.writeValueAsString(event);
                OutboxEventJpaEntity entity = OutboxEventJpaEntity.builder()
                    .id(event.eventId())
                    .aggregateType(event.aggregateType())
                    .aggregateId(event.aggregateId())
                    .eventType(event.getClass().getSimpleName())
                    .payload(payload)
                    .occurredAt(event.occurredAt())
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build();
                entities.add(entity);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize domain event: {}", event, e);
                throw new IllegalStateException("Failed to serialize domain event to JSON", e);
            }
        }

        outboxRepository.saveAll(entities);
        log.debug("Persisted {} domain event(s) to transactional outbox", entities.size());
    }
}
