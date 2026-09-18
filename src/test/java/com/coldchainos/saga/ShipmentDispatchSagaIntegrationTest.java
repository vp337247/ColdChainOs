package com.coldchainos.saga;

import com.coldchainos.saga.dispatch.*;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.shipment.infrastructure.external.ExternalComplianceClearinghouseClient;
import com.coldchainos.tenant.application.TenantProvisioningService;
import com.coldchainos.warehouse.domain.*;
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
 * Phase 12 Integration Test: Distributed Sagas & Orchestration with Compensating Transactions.
 *
 * Verifies:
 * 1. Happy-path end-to-end forward progression through warehouse, carrier, compliance, and shipment modules.
 * 2. Backward compensation releasing warehouse slot when carrier booking is depleted.
 * 3. Backward compensation cancelling carrier booking and releasing slot when regulatory clearinghouse rejects shipment.
 * 4. Idempotent re-execution safety preventing duplicate resource bookings.
 */
@SpringBootTest
public class ShipmentDispatchSagaIntegrationTest {

    @Autowired
    private ShipmentDispatchSagaOrchestrator sagaOrchestrator;

    @Autowired
    private WarehouseSlotRepository slotRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private CarrierBookingService carrierBookingService;

    @Autowired
    private ExternalComplianceClearinghouseClient complianceClient;

    @Autowired
    private DispatchSagaStateJpaRepository sagaRepository;

    @Autowired
    private TenantProvisioningService provisioningService;

    private final TenantId tenantId = TenantId.of("saga_dispatch_lab");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantId, "Saga Dispatch Logistics Hub");
        carrierBookingService.reset();
        complianceClient.reset();
    }

    @Test
    @DisplayName("1. Happy Path: All forward saga steps succeed; shipment transitions to IN_TRANSIT and saga completes")
    void shouldSuccessfullyExecuteHappyPathDispatchSaga() {
        SlotId slotId = createWarehouseSlot();
        Shipment shipment = createCreatedShipment();

        UUID sagaId = UUID.randomUUID();
        DispatchSagaRequest request = new DispatchSagaRequest(
            sagaId,
            tenantId,
            shipment.getId().value(),
            slotId.value(),
            "DHL_COLD_CHAIN",
            "OPERATOR_SARA"
        );

        // Act: Execute saga
        DispatchSagaResult result = sagaOrchestrator.executeDispatchSaga(request);

        // Assert: Saga Result
        assertThat(result.status()).isEqualTo(DispatchSagaStatus.COMPLETED);
        assertThat(result.isCompensated()).isFalse();
        assertThat(result.carrierBookingId()).isNotNull();
        assertThat(result.warehouseSlotId()).isEqualTo(slotId.value());

        // Assert: Shipment state transitioned to IN_TRANSIT
        TenantContext.executeAs(tenantId, () -> {
            Shipment updated = shipmentRepository.findById(shipment.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);

            // Assert: Warehouse Slot has active reservation for this shipment
            WarehouseSlot slot = slotRepository.findById(slotId).orElseThrow();
            assertThat(slot.getReservations()).anyMatch(r -> r.getShipmentId().equals(shipment.getId()) && r.isActive());

            // Assert: Carrier booking is active
            assertThat(carrierBookingService.isBookingActive(result.carrierBookingId())).isTrue();

            // Assert: Saga persistent log
            DispatchSagaStateJpaEntity stateEntity = sagaRepository.findById(sagaId).orElseThrow();
            assertThat(stateEntity.getStatus()).isEqualTo(DispatchSagaStatus.COMPLETED);
            assertThat(stateEntity.getCurrentStep()).isEqualTo("COMPLETED");
        });
    }

    @Test
    @DisplayName("2. Carrier shortage failure: backward compensation releases previously reserved warehouse slot")
    void shouldCompensateAndReleaseWarehouseSlotWhenCarrierBookingFails() {
        SlotId slotId = createWarehouseSlot();
        Shipment shipment = createCreatedShipment();

        // Simulate carrier booking outage/capacity depletion at Step 2
        carrierBookingService.setSimulateCapacityDepleted(true);

        UUID sagaId = UUID.randomUUID();
        DispatchSagaRequest request = new DispatchSagaRequest(
            sagaId,
            tenantId,
            shipment.getId().value(),
            slotId.value(),
            "REEFER_EXPRESS",
            "OPERATOR_MARCUS"
        );

        // Act: Execute saga
        DispatchSagaResult result = sagaOrchestrator.executeDispatchSaga(request);

        // Assert: Saga failed and compensated
        assertThat(result.status()).isEqualTo(DispatchSagaStatus.COMPENSATED_FAILURE);
        assertThat(result.isCompensated()).isTrue();

        TenantContext.executeAs(tenantId, () -> {
            // Assert: Shipment remained in CREATED state (never transitioned to in transit!)
            Shipment updated = shipmentRepository.findById(shipment.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ShipmentStatus.CREATED);

            // Assert: Warehouse slot reservation was compensated and released (NO active reservation!)
            WarehouseSlot slot = slotRepository.findById(slotId).orElseThrow();
            boolean hasActiveReservation = slot.getReservations().stream()
                .anyMatch(r -> r.getShipmentId().equals(shipment.getId()) && r.isActive());
            assertThat(hasActiveReservation).isFalse();

            // Assert: Persistent saga journal recorded failure reason
            DispatchSagaStateJpaEntity stateEntity = sagaRepository.findById(sagaId).orElseThrow();
            assertThat(stateEntity.getStatus()).isEqualTo(DispatchSagaStatus.COMPENSATED_FAILURE);
            assertThat(stateEntity.getFailureReason()).contains("capacity");
        });
    }

    @Test
    @DisplayName("3. Regulatory clearinghouse failure: backward compensation cancels carrier booking and releases warehouse slot")
    void shouldCompensateCarrierBookingAndWarehouseSlotWhenClearinghouseFails() {
        SlotId slotId = createWarehouseSlot();
        Shipment shipment = createCreatedShipment();

        // Simulate Clearinghouse regulatory gateway failure at Step 3
        complianceClient.setForceFailure(true);

        UUID sagaId = UUID.randomUUID();
        DispatchSagaRequest request = new DispatchSagaRequest(
            sagaId,
            tenantId,
            shipment.getId().value(),
            slotId.value(),
            "FEDEX_CUSTOM_CRITICAL",
            "OPERATOR_ELENA"
        );

        // Act: Execute saga
        DispatchSagaResult result = sagaOrchestrator.executeDispatchSaga(request);

        // Assert: Saga status
        assertThat(result.status()).isEqualTo(DispatchSagaStatus.COMPENSATED_FAILURE);
        assertThat(result.isCompensated()).isTrue();

        TenantContext.executeAs(tenantId, () -> {
            // Assert: Shipment remained in CREATED status
            Shipment updated = shipmentRepository.findById(shipment.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ShipmentStatus.CREATED);

            // Assert: Carrier booking was cancelled via compensation
            assertThat(result.carrierBookingId()).isNotNull();
            assertThat(carrierBookingService.isBookingActive(result.carrierBookingId())).isFalse();

            // Assert: Warehouse slot reservation was cancelled via compensation
            WarehouseSlot slot = slotRepository.findById(slotId).orElseThrow();
            boolean hasActiveReservation = slot.getReservations().stream()
                .anyMatch(r -> r.getShipmentId().equals(shipment.getId()) && r.isActive());
            assertThat(hasActiveReservation).isFalse();

            // Assert: Persistent saga journal
            DispatchSagaStateJpaEntity stateEntity = sagaRepository.findById(sagaId).orElseThrow();
            assertThat(stateEntity.getStatus()).isEqualTo(DispatchSagaStatus.COMPENSATED_FAILURE);
        });
    }

    @Test
    @DisplayName("4. Idempotent re-execution safety: re-running completed saga returns recorded outcome without duplicate operations")
    void shouldHandleIdempotentExecutionWithoutDuplicatingReservations() {
        SlotId slotId = createWarehouseSlot();
        Shipment shipment = createCreatedShipment();

        UUID sagaId = UUID.randomUUID();
        DispatchSagaRequest request = new DispatchSagaRequest(
            sagaId,
            tenantId,
            shipment.getId().value(),
            slotId.value(),
            "DHL_COLD_CHAIN",
            "OPERATOR_SARA"
        );

        // First execution
        DispatchSagaResult firstResult = sagaOrchestrator.executeDispatchSaga(request);
        assertThat(firstResult.status()).isEqualTo(DispatchSagaStatus.COMPLETED);

        // Second duplicate execution with same sagaId
        DispatchSagaResult duplicateResult = sagaOrchestrator.executeDispatchSaga(request);
        assertThat(duplicateResult.status()).isEqualTo(DispatchSagaStatus.COMPLETED);
        assertThat(duplicateResult.carrierBookingId()).isEqualTo(firstResult.carrierBookingId());
        assertThat(duplicateResult.warehouseSlotId()).isEqualTo(firstResult.warehouseSlotId());
    }

    private SlotId createWarehouseSlot() {
        return TenantContext.executeAs(tenantId, () -> {
            WarehouseSlot slot = WarehouseSlot.create(
                WarehouseId.of("WH-FRANKFURT-SAGA"),
                "BAY-" + UUID.randomUUID().toString().substring(0, 8),
                ThermalCategory.REFRIGERATED_2_TO_8,
                2500.0
            );
            return slotRepository.save(slot).getId();
        });
    }

    private Shipment createCreatedShipment() {
        return TenantContext.executeAs(tenantId, () -> {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-" + suffix);
            TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.REFRIGERATED_2_TO_8, 600);
            Location origin = Location.of("FRA", "Frankfurt Depot", "Frankfurt", "DE");
            Location destination = Location.of("AMS", "Amsterdam Hub", "Amsterdam", "NL");
            TransitLeg leg = TransitLeg.of(1, origin, destination, Instant.now(), Instant.now().plusSeconds(36000));

            Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, List.of(leg));
            shipmentRepository.save(shipment);
            return shipment;
        });
    }
}
