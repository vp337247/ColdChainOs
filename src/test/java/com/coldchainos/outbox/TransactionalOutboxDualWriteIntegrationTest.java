package com.coldchainos.outbox;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxEventJpaEntity;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxStatus;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.tenant.application.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying dual-write elimination via the Transactional Outbox Pattern.
 * Guarantees that aggregate mutations and corresponding domain events are written atomically
 * to PostgreSQL within the exact same database transaction.
 */
@SpringBootTest
class TransactionalOutboxDualWriteIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private SpringDataOutboxEventRepository outboxRepository;

    private final TenantId tenantId = TenantId.of("outbox_test");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantId, "Outbox Testing Corp");
        TenantContext.executeAs(tenantId, () -> {
            outboxRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("Dual-Write Elimination: Aggregate state change and outbox event are persisted atomically in the same transaction")
    void shouldAtomicallyPersistAggregateAndOutboxEvents() {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-" + suffix);
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.FROZEN_MINUS_20, 300);

        Location origin = Location.of("FRA", "Frankfurt Depot", "Frankfurt", "DE");
        Location dest = Location.of("CDG", "Paris Hub", "Paris", "FR");
        TransitLeg leg = TransitLeg.of(1, origin, dest, Instant.now(), Instant.now().plusSeconds(3600));

        // 1. Create shipment aggregate (triggers ShipmentCreatedEvent)
        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, List.of(leg));

        // 2. Save shipment through repository adapter (persists aggregate + outbox atomically)
        TenantContext.executeAs(tenantId, () -> {
            shipmentRepository.save(shipment);
        });

        // 3. Verify aggregate and outbox event exist within tenant schema
        TenantContext.executeAs(tenantId, () -> {
            // Verify shipment in DB
            Shipment persisted = shipmentRepository.findById(shipment.getId()).orElseThrow();
            assertThat(persisted.getTrackingNumber()).isEqualTo(trackingNumber);

            // Verify outbox event in DB
            List<OutboxEventJpaEntity> pending = outboxRepository.findTop50ByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING);
            assertThat(pending).hasSize(1);

            OutboxEventJpaEntity event = pending.get(0);
            assertThat(event.getAggregateType()).isEqualTo("SHIPMENT");
            assertThat(event.getAggregateId()).isEqualTo(shipment.getId().value().toString());
            assertThat(event.getEventType()).isEqualTo("ShipmentCreatedEvent");
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getPayload()).contains(trackingNumber.value());
            assertThat(event.getPayload()).contains("FROZEN_MINUS_20");
        });

        // 4. Trigger state transition on shipment: Quarantine (triggers ShipmentQuarantinedEvent)
        TenantContext.executeAs(tenantId, () -> {
            Shipment retrieved = shipmentRepository.findById(shipment.getId()).orElseThrow();
            retrieved.applyQuarantine(java.util.UUID.randomUUID(), "High temperature alarm detected at depot");
            shipmentRepository.save(retrieved);
        });

        // 5. Verify second outbox event was recorded
        TenantContext.executeAs(tenantId, () -> {
            List<OutboxEventJpaEntity> pending = outboxRepository.findTop50ByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING);
            assertThat(pending).hasSize(2);

            OutboxEventJpaEntity quarantineEvent = pending.stream()
                .filter(e -> "ShipmentQuarantinedEvent".equals(e.getEventType()))
                .findFirst()
                .orElseThrow();

            assertThat(quarantineEvent.getAggregateId()).isEqualTo(shipment.getId().value().toString());
            assertThat(quarantineEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(quarantineEvent.getPayload()).contains("High temperature alarm detected at depot");
        });
    }
}
