package com.coldchainos.shared.outbox.infrastructure.publisher;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.multitenancy.TenantProvider;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxEventJpaEntity;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxStatus;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Transactional Outbox Relay Publisher.
 *
 * Reliably polls pending outbox events across active tenant schemas and publishes them to Apache Kafka
 * with At-Least-Once delivery guarantees. Attaches tenant context headers and enforces aggregate-level
 * partition keying for strict sequential ordering.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayPublisher {

    public static final String SHIPMENT_EVENTS_TOPIC = "coldchain.shipment.events";

    private final SpringDataOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TenantProvider tenantProvider;

    @org.springframework.beans.factory.annotation.Value("${coldchainos.outbox.relay-enabled:false}")
    private boolean relayEnabled;

    /**
     * Polls and publishes pending events across all active tenant schemas.
     */
    @Scheduled(fixedDelayString = "${coldchainos.outbox.relay-interval-ms:2000}")
    public void publishPendingEventsForAllTenants() {
        if (!relayEnabled) {
            return;
        }

        List<TenantId> activeTenants = tenantProvider.getActiveTenantIds();
        for (TenantId tenantId : activeTenants) {
            try {
                publishPendingEventsForTenant(tenantId);
            } catch (Exception e) {
                log.error("Error executing outbox relay for tenant '{}'", tenantId, e);
            }
        }
    }

    /**
     * Publishes up to 50 pending outbox events for a specific tenant.
     *
     * @return count of successfully published events
     */
    public int publishPendingEventsForTenant(TenantId tenantId) {
        return TenantContext.executeAs(tenantId, () -> {
            List<OutboxEventJpaEntity> pendingEvents = outboxRepository.findTop50ByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING);
            if (pendingEvents.isEmpty()) {
                return 0;
            }

            log.info("Relaying {} pending outbox event(s) to Kafka for tenant '{}'", pendingEvents.size(), tenantId);
            int publishedCount = 0;

            for (OutboxEventJpaEntity event : pendingEvents) {
                boolean success = publishSingleEvent(event, tenantId);
                if (success) {
                    publishedCount++;
                }
            }
            return publishedCount;
        });
    }

    @Transactional
    public boolean publishSingleEvent(OutboxEventJpaEntity event, TenantId tenantId) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                SHIPMENT_EVENTS_TOPIC,
                event.getAggregateId(),
                event.getPayload()
            );

            record.headers().add(new RecordHeader("X-Tenant-ID", tenantId.value().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("X-Event-Type", event.getEventType().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("X-Event-ID", event.getId().toString().getBytes(StandardCharsets.UTF_8)));

            // Block for confirmation from Kafka broker to ensure durable write before updating status in DB
            java.util.concurrent.CompletableFuture<?> future = kafkaTemplate.send(record);
            if (future != null) {
                future.get(5, TimeUnit.SECONDS);
            }

            event.markPublished();
            outboxRepository.save(event);
            log.debug("Successfully published event '{}' (ID: {}) to topic '{}'", event.getEventType(), event.getId(), SHIPMENT_EVENTS_TOPIC);
            return true;
        } catch (Exception e) {
            log.error("Failed to publish outbox event '{}' (ID: {}) to Kafka: {}", event.getEventType(), event.getId(), e.getMessage());
            event.markFailed(e.getMessage());
            outboxRepository.save(event);
            return false;
        }
    }
}
