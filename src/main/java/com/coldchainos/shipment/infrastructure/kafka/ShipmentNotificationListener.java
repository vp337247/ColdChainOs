package com.coldchainos.shipment.infrastructure.kafka;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.idempotency.application.IdempotentConsumerExecutor;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downstream consumer simulating compliance notification dispatch and audit logging.
 * Protected by IdempotentConsumerExecutor to ensure at-least-once Kafka deliveries
 * are processed with effectively-once semantics.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentNotificationListener {

    public static final String CONSUMER_NAME = "ShipmentNotificationService";

    private final IdempotentConsumerExecutor idempotentExecutor;
    private final ObjectMapper objectMapper;

    // In-memory ledger of dispatched notifications for verification & audit
    private final List<DispatchedNotification> dispatchedNotifications = new CopyOnWriteArrayList<>();
    // Tracks highest observed event timestamp per aggregate to fence out-of-order stale arrivals
    private final Map<String, Instant> aggregateWatermarks = new ConcurrentHashMap<>();

    private final AtomicInteger invocationCount = new AtomicInteger(0);

    @KafkaListener(
        topics = "coldchain.shipment.events",
        groupId = "coldchain-shipment-notification-group"
    )
    public void onShipmentEvent(ConsumerRecord<String, String> record) {
        invocationCount.incrementAndGet();

        // 1. Extract headers
        String tenantIdStr = extractHeader(record, "X-Tenant-ID");
        String eventType = extractHeader(record, "X-Event-Type");
        String eventIdStr = extractHeader(record, "X-Event-ID");

        if (tenantIdStr == null || eventIdStr == null) {
            log.warn("Skipping malformed event without required headers on partition {} offset {}",
                record.partition(), record.offset());
            return;
        }

        TenantId tenantId = new TenantId(tenantIdStr);
        UUID eventId = UUID.fromString(eventIdStr);
        String aggregateId = record.key();

        // 2. Parse event body to read occurredAt
        Instant occurredAt = Instant.now();
        try {
            JsonNode node = objectMapper.readTree(record.value());
            if (node.has("occurredAt")) {
                occurredAt = Instant.parse(node.get("occurredAt").asText());
            }
        } catch (Exception e) {
            log.warn("Could not parse occurredAt timestamp from event payload: {}", e.getMessage());
        }

        final Instant finalOccurredAt = occurredAt;

        // 3. Execute idempotently within active tenant schema
        TenantContext.executeAs(tenantId, () -> {
            boolean processed = idempotentExecutor.executeIdempotently(
                CONSUMER_NAME,
                eventId,
                eventType != null ? eventType : "UnknownEvent",
                aggregateId,
                () -> {
                    // Out-of-order temporal fencing check
                    Instant lastWatermark = aggregateWatermarks.get(aggregateId);
                    if (lastWatermark != null && finalOccurredAt.isBefore(lastWatermark)) {
                        log.warn("Out-of-order event detected for aggregate '{}'! Event timestamp {} is earlier than watermark {}. Fencing state mutation.",
                            aggregateId, finalOccurredAt, lastWatermark);
                        // Still recorded in idempotency log so it won't re-execute, but we don't apply state
                        return;
                    }

                    // Update watermark
                    aggregateWatermarks.put(aggregateId, finalOccurredAt);

                    // Dispatch business notification
                    dispatchedNotifications.add(new DispatchedNotification(
                        tenantId,
                        aggregateId,
                        eventType,
                        eventId,
                        finalOccurredAt,
                        Instant.now()
                    ));
                    log.info("DISPATCHED compliance notification for event '{}' on shipment '{}' (Tenant: {})",
                        eventType, aggregateId, tenantId);
                }
            );

            if (!processed) {
                log.info("Discarded duplicate event '{}' (ID: {}) for shipment '{}'",
                    eventType, eventId, aggregateId);
            }
        });
    }

    private String extractHeader(ConsumerRecord<String, String> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }

    public List<DispatchedNotification> getDispatchedNotifications() {
        return Collections.unmodifiableList(dispatchedNotifications);
    }

    public int getInvocationCount() {
        return invocationCount.get();
    }

    public void clear() {
        dispatchedNotifications.clear();
        aggregateWatermarks.clear();
        invocationCount.set(0);
    }

    public record DispatchedNotification(
        TenantId tenantId,
        String aggregateId,
        String eventType,
        UUID eventId,
        Instant occurredAt,
        Instant dispatchedAt
    ) {}
}
