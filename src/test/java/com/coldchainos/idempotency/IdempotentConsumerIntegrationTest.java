package com.coldchainos.idempotency;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.idempotency.application.IdempotentConsumerExecutor;
import com.coldchainos.shared.idempotency.infrastructure.persistence.ConsumedEventJpaEntity;
import com.coldchainos.shared.idempotency.infrastructure.persistence.ConsumedEventJpaRepository;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.events.ShipmentQuarantinedEvent;
import com.coldchainos.shipment.infrastructure.kafka.ShipmentNotificationListener;
import com.coldchainos.tenant.application.TenantProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 Integration Test: Idempotent Consumer & Deduplication Engine.
 *
 * Verifies:
 * 1. Concurrent duplicate delivery: Multiple simultaneous attempts to process the exact same event ID
 *    result in exactly one execution and one database ledger entry.
 * 2. Out-of-order temporal fencing: Stale/lagging events arriving after newer events are fenced.
 * 3. Multi-tenant schema isolation: Identical event IDs across different tenants operate independently.
 * 4. End-to-end Kafka delivery: At-least-once Kafka duplicates are deduplicated effectively-once.
 */
@SpringBootTest
class IdempotentConsumerIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private IdempotentConsumerExecutor idempotentExecutor;

    @Autowired
    private ConsumedEventJpaRepository consumedEventRepository;

    @Autowired
    private ShipmentNotificationListener notificationListener;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private final TenantId tenantAlpha = TenantId.of("idemp_alpha");
    private final TenantId tenantBeta = TenantId.of("idemp_beta");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantAlpha, "Idempotency Alpha Corp");
        provisioningService.provisionTenant(tenantBeta, "Idempotency Beta Corp");

        TenantContext.executeAs(tenantAlpha, () -> consumedEventRepository.deleteAll());
        TenantContext.executeAs(tenantBeta, () -> consumedEventRepository.deleteAll());

        notificationListener.clear();
    }

    @Test
    @DisplayName("Should process exactly once under high concurrency when 5 threads race with identical event ID")
    void shouldProcessExactlyOnceUnderConcurrentDuplicateDelivery() throws Exception {
        UUID eventId = UUID.randomUUID();
        String consumerName = "BillingLedgerService";
        String aggregateId = UUID.randomUUID().toString();
        String eventType = "ShipmentDeliveredEvent";

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger executionCounter = new AtomicInteger(0);
        AtomicInteger duplicateSkipCounter = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize all threads to trigger simultaneously
                    TenantContext.executeAs(tenantAlpha, () -> {
                        boolean executed = idempotentExecutor.executeIdempotently(
                            consumerName,
                            eventId,
                            eventType,
                            aggregateId,
                            () -> {
                                executionCounter.incrementAndGet();
                            }
                        );
                        if (!executed) {
                            duplicateSkipCounter.incrementAndGet();
                        }
                    });
                } catch (Exception e) {
                    // Ignore expected constraint violation skips
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        // Assert: Business logic executed EXACTLY ONCE
        assertThat(executionCounter.get())
            .as("Business mutation must execute exactly once regardless of thread concurrency")
            .isEqualTo(1);

        // Assert: Exactly 4 calls were recognized and discarded as duplicates
        assertThat(duplicateSkipCounter.get())
            .as("All 4 racing duplicate calls must be reported as duplicate skips")
            .isEqualTo(4);

        // Assert: Exactly 1 row in PostgreSQL consumed_events under tenantAlpha
        TenantContext.executeAs(tenantAlpha, () -> {
            List<ConsumedEventJpaEntity> records = consumedEventRepository.findByConsumerName(consumerName);
            assertThat(records).hasSize(1);
            assertThat(records.get(0).getEventId()).isEqualTo(eventId);
            assertThat(records.get(0).getAggregateId()).isEqualTo(aggregateId);
        });
    }

    @Test
    @DisplayName("Should fence out-of-order stale events arriving after a newer event has already been processed")
    void shouldFenceOutOfOrderStaleEvents() {
        String shipmentId = UUID.randomUUID().toString();
        Instant tNewer = Instant.now();
        Instant tOlder = tNewer.minusSeconds(300); // 5 minutes earlier

        UUID eventIdNewer = UUID.randomUUID();
        UUID eventIdOlder = UUID.randomUUID();

        // 1. Process newer event (T = 12:00:00)
        simulateKafkaDelivery(tenantAlpha, shipmentId, "CustodyTransferredEvent", eventIdNewer, tNewer);

        // 2. Delayed/stale event arrives out of order (T = 11:55:00)
        simulateKafkaDelivery(tenantAlpha, shipmentId, "ShipmentCreatedEvent", eventIdOlder, tOlder);

        // Assert: Only the newer event was dispatched; the older was fenced
        List<ShipmentNotificationListener.DispatchedNotification> dispatched = notificationListener.getDispatchedNotifications();
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).eventId()).isEqualTo(eventIdNewer);
        assertThat(dispatched.get(0).eventType()).isEqualTo("CustodyTransferredEvent");

        // Assert: Both events are in consumed_events so the stale event won't be retried
        TenantContext.executeAs(tenantAlpha, () -> {
            assertThat(consumedEventRepository.existsByConsumerNameAndEventId(ShipmentNotificationListener.CONSUMER_NAME, eventIdNewer)).isTrue();
            assertThat(consumedEventRepository.existsByConsumerNameAndEventId(ShipmentNotificationListener.CONSUMER_NAME, eventIdOlder)).isTrue();
        });
    }

    @Test
    @DisplayName("Should maintain multi-tenant idempotency isolation when two tenants process the same event ID")
    void shouldIsolateIdempotencyLedgerAcrossTenants() {
        UUID sharedEventId = UUID.randomUUID();
        String consumerName = "GlobalComplianceAuditor";

        // Tenant Alpha processes event
        TenantContext.executeAs(tenantAlpha, () -> {
            boolean processedAlpha = idempotentExecutor.executeIdempotently(
                consumerName, sharedEventId, "AuditReportEvent", "AGG-1", () -> {}
            );
            assertThat(processedAlpha).isTrue();
        });

        // Tenant Beta processes the exact same event ID in its own schema
        TenantContext.executeAs(tenantBeta, () -> {
            boolean processedBeta = idempotentExecutor.executeIdempotently(
                consumerName, sharedEventId, "AuditReportEvent", "AGG-2", () -> {}
            );
            assertThat(processedBeta)
                .as("Tenant Beta's idempotency ledger must not collide with Tenant Alpha's schema")
                .isTrue();
        });

        // Assert: Each schema has its own isolated record
        TenantContext.executeAs(tenantAlpha, () -> {
            assertThat(consumedEventRepository.existsByConsumerNameAndEventId(consumerName, sharedEventId)).isTrue();
        });
        TenantContext.executeAs(tenantBeta, () -> {
            assertThat(consumedEventRepository.existsByConsumerNameAndEventId(consumerName, sharedEventId)).isTrue();
        });
    }

    @Test
    @DisplayName("Should process at-least-once Kafka duplicates effectively once via ShipmentNotificationListener")
    void shouldDeduplicateAtLeastOnceKafkaDeliveries() throws Exception {
        UUID eventId = UUID.randomUUID();
        ShipmentId shipmentId = ShipmentId.newId();
        Instant occurredAt = Instant.now();

        ShipmentQuarantinedEvent event = new ShipmentQuarantinedEvent(
            eventId,
            occurredAt,
            tenantAlpha,
            shipmentId,
            UUID.randomUUID(),
            "Temperature reached +15.5 C in refrigerated cargo"
        );

        String jsonPayload = objectMapper.writeValueAsString(event);

        // Publish identical Kafka message twice (simulating broker duplicate redelivery)
        publishToKafka(tenantAlpha, shipmentId.value().toString(), "ShipmentQuarantinedEvent", eventId, jsonPayload);
        publishToKafka(tenantAlpha, shipmentId.value().toString(), "ShipmentQuarantinedEvent", eventId, jsonPayload);

        // Wait up to 10 seconds for Kafka consumer to process both deliveries
        awaitUntil(() -> notificationListener.getInvocationCount() >= 2, 10);

        // Assert: Invocation count is 2, but dispatched notification count is EXACTLY 1!
        assertThat(notificationListener.getInvocationCount()).isGreaterThanOrEqualTo(2);
        assertThat(notificationListener.getDispatchedNotifications())
            .as("Exactly one compliance notification must be dispatched despite duplicate Kafka delivery")
            .hasSize(1);

        // Assert: Exactly 1 record in PostgreSQL consumed_events
        TenantContext.executeAs(tenantAlpha, () -> {
            List<ConsumedEventJpaEntity> list = consumedEventRepository.findByConsumerName(ShipmentNotificationListener.CONSUMER_NAME);
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getEventId()).isEqualTo(eventId);
        });
    }

    private void simulateKafkaDelivery(TenantId tenantId, String aggregateId, String eventType, UUID eventId, Instant occurredAt) {
        String json = "{\"occurredAt\":\"" + occurredAt.toString() + "\",\"aggregateId\":\"" + aggregateId + "\"}";
        org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record =
            new org.apache.kafka.clients.consumer.ConsumerRecord<>("coldchain.shipment.events", 0, 0L, aggregateId, json);
        record.headers().add(new RecordHeader("X-Tenant-ID", tenantId.value().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Event-Type", eventType.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Event-ID", eventId.toString().getBytes(StandardCharsets.UTF_8)));

        notificationListener.onShipmentEvent(record);
    }

    private void publishToKafka(TenantId tenantId, String key, String eventType, UUID eventId, String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>("coldchain.shipment.events", key, payload);
        record.headers().add(new RecordHeader("X-Tenant-ID", tenantId.value().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Event-Type", eventType.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("X-Event-ID", eventId.toString().getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        assertThat(condition.getAsBoolean()).as("Condition was not satisfied within " + timeoutSeconds + "s").isTrue();
    }
}
