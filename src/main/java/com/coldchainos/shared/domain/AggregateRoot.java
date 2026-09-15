package com.coldchainos.shared.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Base class for all DDD Aggregate Roots.
 * Manages in-memory domain events that are registered during state changes
 * and dispatched atomically when the aggregate is persisted (e.g., via Outbox).
 */
public abstract class AggregateRoot<ID> {

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    public abstract ID getId();

    /**
     * Registers a domain event triggered by a state change on this aggregate.
     */
    protected void registerEvent(DomainEvent event) {
        Objects.requireNonNull(event, "DomainEvent cannot be null");
        this.domainEvents.add(event);
    }

    /**
     * Returns an unmodifiable view of pending domain events.
     */
    public List<DomainEvent> getDomainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    /**
     * Clears all domain events after they have been published / persisted to outbox.
     */
    public void clearDomainEvents() {
        this.domainEvents.clear();
    }
}
