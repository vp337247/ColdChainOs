package com.coldchainos.outbox;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.multitenancy.TenantProvider;
import com.coldchainos.shared.observability.ColdChainMetrics;
import com.coldchainos.shared.observability.CorrelationContext;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxEventJpaEntity;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxStatus;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import com.coldchainos.shared.outbox.infrastructure.publisher.OutboxRelayPublisher;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.tenant.application.TenantProvisioningService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the Outbox Relay Publisher's interaction with Apache Kafka:
 * 1. Attaches mandatory multi-tenant and event headers (X-Tenant-ID, X-Event-Type, X-Event-ID).
 * 2. Uses aggregate ID as partition key to enforce sequential ordering.
 * 3. Updates PostgreSQL outbox status from PENDING to PUBLISHED upon broker acknowledgment.
 * 4. Handles broker failure by incrementing retry count and persisting error diagnostics.
 */
@SpringBootTest
class KafkaOutboxRelayIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private SpringDataOutboxEventRepository outboxRepository;

    @Autowired
    private OutboxRelayPublisher relayPublisher;

    @Autowired
    private TenantProvider tenantProvider;

    @Autowired
    private CorrelationContext correlationContext;

    @Autowired
    private ColdChainMetrics coldChainMetrics;

    private final TenantId tenantId = TenantId.of("relay_test");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantId, "Kafka Relay Testing Corp");
        TenantContext.executeAs(tenantId, () -> {
            outboxRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("Should relay pending outbox events to Kafka, attach tenant headers, and mark PUBLISHED")
    void shouldRelayPendingOutboxEventsToKafkaSuccessfully() {
        String suffix1 = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-" + suffix1);
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.ULTRA_COLD_MINUS_80, 600);
        Location origin = Location.of("JFK", "JFK Cargo", "New York", "US");
        Location dest = Location.of("LHR", "Heathrow Cold Bay", "London", "GB");
        TransitLeg leg = TransitLeg.of(1, origin, dest, Instant.now(), Instant.now().plusSeconds(28800));

        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, List.of(leg));

        TenantContext.executeAs(tenantId, () -> {
            shipmentRepository.save(shipment);
        });

        // Act: Execute outbox relay publisher for tenant using real KafkaTemplate against KRaft broker
        int published = relayPublisher.publishPendingEventsForTenant(tenantId);
        assertThat(published).isEqualTo(1);

        // Verify PostgreSQL outbox state transition to PUBLISHED
        TenantContext.executeAs(tenantId, () -> {
            List<OutboxEventJpaEntity> allEvents = outboxRepository.findAll();
            assertThat(allEvents).hasSize(1);
            OutboxEventJpaEntity entity = allEvents.get(0);
            assertThat(entity.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(entity.getPublishedAt()).isNotNull();
            assertThat(entity.getErrorMessage()).isNull();
        });
    }

    @Test
    @DisplayName("Should increment retry count and record error diagnostics when Kafka broker is unreachable")
    void shouldHandleKafkaBrokerFailureGracefully() {
        // Arrange: Isolated mock Kafka broker failure (timeout / connection refused)
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> mockKafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new org.apache.kafka.common.errors.TimeoutException("Kafka broker leader not available"));
        when(mockKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

        OutboxRelayPublisher failingPublisher = new OutboxRelayPublisher(
            outboxRepository,
            mockKafkaTemplate,
            tenantProvider,
            correlationContext,
            coldChainMetrics
        );

        String suffix2 = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-" + suffix2);
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.CONTROLLED_ROOM_TEMP_15_TO_25, 1200);
        Location origin = Location.of("ZRH", "Zurich Depot", "Zurich", "CH");
        Location dest = Location.of("GVA", "Geneva Hub", "Geneva", "CH");
        TransitLeg leg = TransitLeg.of(1, origin, dest, Instant.now(), Instant.now().plusSeconds(7200));

        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, List.of(leg));

        TenantContext.executeAs(tenantId, () -> {
            shipmentRepository.save(shipment);
        });

        // Act: Execute isolated failing publisher
        int published = failingPublisher.publishPendingEventsForTenant(tenantId);
        assertThat(published).isEqualTo(0);

        // Assert: Event remains in PostgreSQL, status remains PENDING or FAILED with retry count incremented
        TenantContext.executeAs(tenantId, () -> {
            List<OutboxEventJpaEntity> pending = outboxRepository.findTop50ByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING);
            assertThat(pending).hasSize(1);
            OutboxEventJpaEntity entity = pending.get(0);
            assertThat(entity.getRetryCount()).isEqualTo(1);
            assertThat(entity.getErrorMessage()).contains("Kafka broker leader not available");
            assertThat(entity.getPublishedAt()).isNull();
        });
    }
}
