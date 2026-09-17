package com.coldchainos.shared.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Base marker interface for pure domain events within the domain model.
 * In accordance with DDD principles and ArchUnit fitness functions,
 * domain events must remain completely decoupled from serialization and transport frameworks.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    String aggregateType();

    String aggregateId();
}
