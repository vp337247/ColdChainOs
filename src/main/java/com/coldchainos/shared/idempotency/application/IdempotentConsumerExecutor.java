package com.coldchainos.shared.idempotency.application;

import com.coldchainos.shared.idempotency.infrastructure.persistence.ConsumedEventJpaEntity;
import com.coldchainos.shared.idempotency.infrastructure.persistence.ConsumedEventJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Enterprise Idempotent Consumer execution engine.
 * Ensures downstream consumers process events with effectively-once business semantics.
 * Employs insert-first reservation to guarantee business actions execute exactly once even
 * under aggressive multi-threaded duplicate deliveries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotentConsumerExecutor {

    private final ConsumedEventJpaRepository consumedEventRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * Executes the given action idempotently within the active tenant's transaction.
     *
     * @return true if the event was processed successfully; false if discarded as a duplicate
     */
    public boolean executeIdempotently(
            String consumerName,
            UUID eventId,
            String eventType,
            String aggregateId,
            Runnable businessAction) {

        return executeIdempotently(consumerName, eventId, eventType, aggregateId, () -> {
            businessAction.run();
            return true;
        }).isPresent();
    }

    /**
     * Executes the supplier idempotently within the active tenant's transaction and returns the result.
     * Uses insert-first reservation: acquires the unique database claim before executing the supplier,
     * guaranteeing the business action never runs more than once under concurrent duplicate delivery.
     *
     * @return Optional containing the action result if unique; empty Optional if discarded as duplicate
     */
    public <T> Optional<T> executeIdempotently(
            String consumerName,
            UUID eventId,
            String eventType,
            String aggregateId,
            Supplier<T> businessSupplier) {

        // 1. Fast read check to bypass transaction overhead for standard sequential duplicates
        if (consumedEventRepository.existsByConsumerNameAndEventId(consumerName, eventId)) {
            log.info("Duplicate event '{}' (ID: {}) already processed by consumer '{}'. Skipping.",
                eventType, eventId, consumerName);
            return Optional.empty();
        }

        try {
            return transactionTemplate.execute(status -> {
                // 2. Insert-first deduplication reservation: locks the unique claim in PostgreSQL
                ConsumedEventJpaEntity consumed = new ConsumedEventJpaEntity(
                    UUID.randomUUID(),
                    eventId,
                    consumerName,
                    eventType,
                    aggregateId,
                    Instant.now()
                );
                consumedEventRepository.saveAndFlush(consumed);

                // 3. Only executed if the database reservation succeeded without constraint violation
                T result = businessSupplier.get();

                log.debug("Event '{}' (ID: {}) successfully processed and recorded by consumer '{}'",
                    eventType, eventId, consumerName);
                return Optional.ofNullable(result);
            });
        } catch (DataIntegrityViolationException e) {
            // 4. Concurrent duplicate race caught cleanly by PostgreSQL unique constraint
            log.warn("Concurrent duplicate race detected for event '{}' (ID: {}) on consumer '{}'. Safely discarded.",
                eventType, eventId, consumerName);
            return Optional.empty();
        }
    }
}
