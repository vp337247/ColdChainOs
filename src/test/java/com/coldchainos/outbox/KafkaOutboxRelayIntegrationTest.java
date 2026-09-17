package com.coldchainos.outbox;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxEventJpaEntity;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxStatus;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import com.coldchainos.shared.outbox.infrastructure.publisher.OutboxRelayPublisher;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.tenant.application.TenantProvisioningService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

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
        // Arrange: Mock Kafka producer acknowledgment
        SendResult<String, String> mockSendResult = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(mockSendResult));

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

        // Act: Execute outbox relay publisher for tenant
        int published = relayPublisher.publishPendingEventsForTenant(tenantId);
        assertThat(published).isEqualTo(1);

        // Assert: Verify Kafka record contents and headers
        ArgumentCaptor<ProducerRecord<String, String>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(1)).send(recordCaptor.capture());

        ProducerRecord<String, String> capturedRecord = recordCaptor.getValue();
        assertThat(capturedRecord.topic()).isEqualTo(OutboxRelayPublisher.SHIPMENT_EVENTS_TOPIC);
        assertThat(capturedRecord.key()).isEqualTo(shipment.getId().value().toString());

        Header tenantHeader = capturedRecord.headers().lastHeader("X-Tenant-ID");
        assertThat(tenantHeader).isNotNull();
        assertThat(new String(tenantHeader.value(), StandardCharsets.UTF_8)).isEqualTo(tenantId.value());

        Header eventTypeHeader = capturedRecord.headers().lastHeader("X-Event-Type");
        assertThat(eventTypeHeader).isNotNull();
        assertThat(new String(eventTypeHeader.value(), StandardCharsets.UTF_8)).isEqualTo("ShipmentCreatedEvent");

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
        // Arrange: Mock Kafka broker failure (timeout / connection refused)
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new org.apache.kafka.common.errors.TimeoutException("Kafka broker leader not available"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

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

        // Act: Execute relay publisher (expecting broker failure)
        int published = relayPublisher.publishPendingEventsForTenant(tenantId);
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
