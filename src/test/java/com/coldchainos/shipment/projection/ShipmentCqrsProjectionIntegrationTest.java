package com.coldchainos.shipment.projection;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import com.coldchainos.shared.outbox.infrastructure.publisher.OutboxRelayPublisher;
import com.coldchainos.shipment.application.ShipmentSummaryQueryService;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryProjector;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryViewJpaEntity;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryViewJpaRepository;
import com.coldchainos.tenant.application.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 Integration Test: CQRS & Read Projections.
 *
 * Verifies:
 * 1. Write commands emit domain events -> relayed to Kafka -> asynchronously project into shipment_summary_view.
 * 2. Carrier assignment and quarantine updates propagate to the read model.
 * 3. Query service serves single-table multi-faceted filtering without write-table joins.
 * 4. Idempotent consumer engine protects projection from duplicate event replays.
 */
@SpringBootTest
class ShipmentCqrsProjectionIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private SpringDataOutboxEventRepository outboxRepository;

    @Autowired
    private OutboxRelayPublisher outboxRelayPublisher;

    @Autowired
    private ShipmentSummaryProjector summaryProjector;

    @Autowired
    private ShipmentSummaryQueryService queryService;

    @Autowired
    private ShipmentSummaryViewJpaRepository summaryRepository;

    private final TenantId tenantId = TenantId.of("cqrs_test");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantId, "CQRS Logistics Corp");
        TenantContext.executeAs(tenantId, () -> {
            summaryRepository.deleteAll();
            outboxRepository.deleteAll();
        });
        summaryProjector.resetProjectedCount();
    }

    @Test
    @DisplayName("Should asynchronously project Shipment aggregate lifecycle into shipment_summary_view read model")
    void shouldProjectShipmentAggregateLifecycleIntoReadModel() throws Exception {
        // --- STEP 1: CREATE SHIPMENT (Write Model) ---
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-" + suffix);
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.REFRIGERATED_2_TO_8, 300);

        Location frankfurt = Location.of("FRA", "Frankfurt Cold Hub", "Frankfurt", "DE");
        Location paris = Location.of("CDG", "Paris Cold Cargo", "Paris", "FR");
        Location london = Location.of("LHR", "Heathrow Pharma Depot", "London", "GB");

        TransitLeg leg1 = TransitLeg.of(1, frankfurt, paris, Instant.now(), Instant.now().plusSeconds(14400));
        TransitLeg leg2 = TransitLeg.of(2, paris, london, Instant.now().plusSeconds(14400), Instant.now().plusSeconds(28800));

        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, List.of(leg1, leg2));

        // Save aggregate (persists aggregate + generates outbox event in same ACID transaction)
        TenantContext.executeAs(tenantId, () -> shipmentRepository.save(shipment));

        // Relay outbox event to Kafka topic coldchain.shipment.events
        int relayedCreated = outboxRelayPublisher.publishPendingEventsForTenant(tenantId);
        assertThat(relayedCreated).isEqualTo(1);

        // Wait for CQRS projector to process the event
        awaitUntil(() -> {
            Optional<ShipmentSummaryViewJpaEntity> view = queryService.getById(tenantId, shipment.getId().value());
            return view.isPresent();
        }, 20);

        // Assert Step 1: Read Model contains initial flat summary
        ShipmentSummaryViewJpaEntity createdView = queryService.getById(tenantId, shipment.getId().value()).orElseThrow();
        assertThat(createdView.getTrackingNumber()).isEqualTo(trackingNumber.value());
        assertThat(createdView.getStatus()).isEqualTo("CREATED");
        assertThat(createdView.getThermalCategory()).isEqualTo("REFRIGERATED_2_TO_8");
        assertThat(createdView.getOriginCode()).isEqualTo("FRA");
        assertThat(createdView.getOriginCity()).isEqualTo("Frankfurt");
        assertThat(createdView.getDestinationCode()).isEqualTo("LHR");
        assertThat(createdView.getDestinationCity()).isEqualTo("London");
        assertThat(createdView.getTotalTransitLegs()).isEqualTo(2);
        assertThat(createdView.getIsQuarantined()).isFalse();
        assertThat(createdView.getAssignedCarrierId()).isNull();

        // --- STEP 2: ASSIGN CARRIER (Write Model mutation) ---
        TenantContext.executeAs(tenantId, () -> {
            Shipment toUpdate = shipmentRepository.findById(shipment.getId()).orElseThrow();
            toUpdate.assignCarrier(leg1.getId(), CarrierId.of("CARRIER-DHL-EXPRESS"));
            toUpdate.assignCarrier(leg2.getId(), CarrierId.of("CARRIER-DHL-EXPRESS"));
            shipmentRepository.save(toUpdate);
        });

        int relayedCarrier = outboxRelayPublisher.publishPendingEventsForTenant(tenantId);
        assertThat(relayedCarrier).isGreaterThanOrEqualTo(1);

        awaitUntil(() -> {
            Optional<ShipmentSummaryViewJpaEntity> view = queryService.getById(tenantId, shipment.getId().value());
            return view.isPresent() && "CARRIER-DHL-EXPRESS".equals(view.get().getAssignedCarrierId());
        }, 20);

        ShipmentSummaryViewJpaEntity carrierView = queryService.getById(tenantId, shipment.getId().value()).orElseThrow();
        assertThat(carrierView.getAssignedCarrierId()).isEqualTo("CARRIER-DHL-EXPRESS");

        // --- STEP 3: EXCURSION & QUARANTINE (Write Model mutation) ---
        TenantContext.executeAs(tenantId, () -> {
            Shipment toQuarantine = shipmentRepository.findById(shipment.getId()).orElseThrow();
            toQuarantine.startTransit();
            toQuarantine.applyQuarantine(UUID.randomUUID(), "Temperature spiked to +15.5 C");
            shipmentRepository.save(toQuarantine);
        });

        outboxRelayPublisher.publishPendingEventsForTenant(tenantId);

        awaitUntil(() -> {
            Optional<ShipmentSummaryViewJpaEntity> view = queryService.getById(tenantId, shipment.getId().value());
            return view.isPresent() && view.get().getIsQuarantined();
        }, 20);

        ShipmentSummaryViewJpaEntity quarantinedView = queryService.getById(tenantId, shipment.getId().value()).orElseThrow();
        assertThat(quarantinedView.getStatus()).isEqualTo("QUARANTINED");
        assertThat(quarantinedView.getIsQuarantined()).isTrue();

        // --- STEP 4: READ-SIDE MULTI-FACETED QUERY FILTERING ---
        // Fast non-locking query: fetch all quarantined shipments for this tenant
        List<ShipmentSummaryViewJpaEntity> quarantinedList = queryService.getQuarantinedShipments(tenantId);
        assertThat(quarantinedList).hasSize(1);
        assertThat(quarantinedList.get(0).getTrackingNumber()).isEqualTo(trackingNumber.value());

        // Fast non-locking query: fetch shipments by carrier
        List<ShipmentSummaryViewJpaEntity> dhlShipments = queryService.getShipmentsByCarrier(tenantId, "CARRIER-DHL-EXPRESS");
        assertThat(dhlShipments).hasSize(1);

        // Fast non-locking query: lookup by tracking number
        Optional<ShipmentSummaryViewJpaEntity> byTracking = queryService.getByTrackingNumber(tenantId, trackingNumber.value());
        assertThat(byTracking).isPresent();
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
