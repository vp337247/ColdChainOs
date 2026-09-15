package com.coldchainos.shared.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Base interface for all immutable Domain Events in ColdChainOS.
 * Domain Events represent facts that have occurred in the business domain.
 */
public interface DomainEvent {

    /**
     * Unique identifier for this event occurrence (for deduplication / idempotency).
     */
    UUID eventId();

    /**
     * UTC timestamp when the domain event occurred.
     */
    Instant occurredAt();
}
