package com.coldchainos.shared.idempotency.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consumed_events")
@Getter
@Setter
@NoArgsConstructor
public class ConsumedEventJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "consumer_name", nullable = false, length = 128)
    private String consumerName;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    public ConsumedEventJpaEntity(UUID id, UUID eventId, String consumerName, String eventType, String aggregateId, Instant consumedAt) {
        this.id = id;
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.consumedAt = consumedAt != null ? consumedAt : Instant.now();
    }
}
