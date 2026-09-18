package com.coldchainos.saga.dispatch;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.shipment.infrastructure.external.ComplianceAssessment;
import com.coldchainos.shipment.infrastructure.external.ComplianceClearinghouseClient;
import com.coldchainos.shipment.infrastructure.external.ComplianceStatus;
import com.coldchainos.warehouse.application.WarehouseSlotReservationService;
import com.coldchainos.warehouse.domain.SlotId;
import com.coldchainos.warehouse.domain.TimeWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the multi-module shipment dispatch distributed saga.
 * Enforces forward progression through warehouse, carrier, compliance, and shipment domains,
 * and guarantees reverse compensating rollbacks upon failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentDispatchSagaOrchestrator {

    private final DispatchSagaStateJpaRepository sagaRepository;
    private final WarehouseSlotReservationService warehouseService;
    private final CarrierBookingService carrierService;
    private final ComplianceClearinghouseClient complianceClient;
    private final ShipmentRepository shipmentRepository;

    /**
     * Executes the shipment dispatch saga across warehouse, carrier, compliance, and shipment modules.
     */
    public DispatchSagaResult executeDispatchSaga(DispatchSagaRequest request) {
        TenantId tenantId = request.tenantId();
        UUID sagaId = request.sagaId();
        UUID shipmentId = request.shipmentId();

        log.info("[Saga {}] Initiating shipment dispatch saga for shipment '{}' under tenant '{}'",
            sagaId, shipmentId, tenantId.value());

        return TenantContext.executeAs(tenantId, () -> {
            // Idempotency check: return existing state if already recorded
            Optional<DispatchSagaStateJpaEntity> existingSaga = sagaRepository.findById(sagaId);
            if (existingSaga.isPresent()) {
                DispatchSagaStateJpaEntity entity = existingSaga.get();
                log.info("[Saga {}] Saga already exists with status '{}'", sagaId, entity.getStatus());
                return new DispatchSagaResult(
                    sagaId,
                    shipmentId,
                    entity.getStatus(),
                    entity.getWarehouseSlotId(),
                    entity.getCarrierBookingId(),
                    entity.getStatus() == DispatchSagaStatus.COMPENSATED_FAILURE,
                    "Idempotent response: saga already in state " + entity.getStatus()
                );
            }

            // Initialize durable saga journal
            DispatchSagaStateJpaEntity sagaState = new DispatchSagaStateJpaEntity(
                sagaId,
                shipmentId,
                DispatchSagaStatus.STARTED,
                "INITIATED"
            );
            sagaRepository.saveAndFlush(sagaState);

            UUID reservedSlotId = null;
            String carrierBookingRef = null;

            try {
                // ====================================================================
                // STEP 1: Reserve Warehouse Staging Bay & Dock Slot
                // ====================================================================
                log.info("[Saga {}] Step 1: Reserving warehouse staging slot '{}'", sagaId, request.warehouseSlotId());
                sagaState.setCurrentStep("RESERVE_WAREHOUSE_SLOT");
                sagaRepository.saveAndFlush(sagaState);

                TimeWindow window = TimeWindow.of(
                    Instant.now(),
                    Instant.now().plus(4, ChronoUnit.HOURS)
                );
                warehouseService.reserveWithPessimisticLock(
                    new SlotId(request.warehouseSlotId()),
                    new ShipmentId(shipmentId),
                    window
                );
                reservedSlotId = request.warehouseSlotId();
                sagaState.setWarehouseSlotId(reservedSlotId);
                sagaState.setStatus(DispatchSagaStatus.WAREHOUSE_SLOT_RESERVED);
                sagaRepository.saveAndFlush(sagaState);

                // ====================================================================
                // STEP 2: Contract Certified Reefer Carrier Transport
                // ====================================================================
                log.info("[Saga {}] Step 2: Contracting certified reefer carrier '{}'", sagaId, request.carrierId());
                sagaState.setCurrentStep("BOOK_CARRIER");
                sagaRepository.saveAndFlush(sagaState);

                carrierBookingRef = carrierService.bookCarrier(tenantId, shipmentId, request.carrierId());
                sagaState.setCarrierBookingId(carrierBookingRef);
                sagaState.setStatus(DispatchSagaStatus.CARRIER_BOOKED);
                sagaRepository.saveAndFlush(sagaState);

                // ====================================================================
                // STEP 3: Regulatory Compliance Clearinghouse Verification
                // ====================================================================
                log.info("[Saga {}] Step 3: Verifying clearinghouse regulatory status", sagaId);
                sagaState.setCurrentStep("VERIFY_COMPLIANCE");
                sagaRepository.saveAndFlush(sagaState);

                ComplianceAssessment compliance = complianceClient.verifyRegulatoryClearance(
                    tenantId.value(),
                    shipmentId.toString()
                );

                if (compliance.status() != ComplianceStatus.APPROVED) {
                    throw new IllegalStateException("Regulatory clearinghouse withheld approval: " + compliance.notes());
                }
                sagaState.setStatus(DispatchSagaStatus.COMPLIANCE_VERIFIED);
                sagaRepository.saveAndFlush(sagaState);

                // ====================================================================
                // STEP 4: Confirm Shipment Dispatch (Transition to IN_TRANSIT)
                // ====================================================================
                log.info("[Saga {}] Step 4: Finalizing shipment dispatch aggregate", sagaId);
                sagaState.setCurrentStep("DISPATCH_SHIPMENT");
                sagaRepository.saveAndFlush(sagaState);

                Shipment shipment = shipmentRepository.findById(new ShipmentId(shipmentId))
                    .orElseThrow(() -> new IllegalArgumentException("Shipment not found: " + shipmentId));

                if (shipment.getStatus() == ShipmentStatus.CREATED) {
                    TransitLeg firstLeg = shipment.getLegs().get(0);
                    shipment.assignCarrier(firstLeg.getId(), CarrierId.of(request.carrierId()));
                }
                if (shipment.getStatus() == ShipmentStatus.ASSIGNED) {
                    shipment.startTransit();
                }
                shipmentRepository.save(shipment);

                // Mark saga COMPLETED
                sagaState.setStatus(DispatchSagaStatus.COMPLETED);
                sagaState.setCurrentStep("COMPLETED");
                sagaState.setUpdatedAt(Instant.now());
                sagaRepository.saveAndFlush(sagaState);

                log.info("[Saga {}] Dispatch saga successfully COMPLETED for shipment '{}'", sagaId, shipmentId);
                return new DispatchSagaResult(
                    sagaId,
                    shipmentId,
                    DispatchSagaStatus.COMPLETED,
                    reservedSlotId,
                    carrierBookingRef,
                    false,
                    "Shipment successfully dispatched and in transit"
                );

            } catch (Exception ex) {
                log.error("[Saga {}] Failure encountered at step '{}': {}. Initiating backward compensation...",
                    sagaId, sagaState.getCurrentStep(), ex.getMessage());

                compensate(tenantId, sagaState, reservedSlotId, carrierBookingRef, new ShipmentId(shipmentId), ex.getMessage());

                return new DispatchSagaResult(
                    sagaId,
                    shipmentId,
                    DispatchSagaStatus.COMPENSATED_FAILURE,
                    reservedSlotId,
                    carrierBookingRef,
                    true,
                    "Saga aborted and compensated. Reason: " + ex.getMessage()
                );
            }
        });
    }

    /**
     * Executes compensating transactions in reverse order to restore consistency.
     */
    private void compensate(
        TenantId tenantId,
        DispatchSagaStateJpaEntity sagaState,
        UUID reservedSlotId,
        String carrierBookingRef,
        ShipmentId shipmentId,
        String failureReason
    ) {
        sagaState.setStatus(DispatchSagaStatus.COMPENSATING);
        sagaState.setFailureReason(failureReason);
        sagaState.setUpdatedAt(Instant.now());
        sagaRepository.saveAndFlush(sagaState);

        // Compensation 1: Cancel carrier booking if allocated
        if (carrierBookingRef != null) {
            try {
                log.info("[Saga Compensation] Cancelling carrier booking '{}'", carrierBookingRef);
                carrierService.cancelCarrierBooking(tenantId, shipmentId.value(), carrierBookingRef);
            } catch (Exception e) {
                log.error("[Saga Compensation] Failed to cancel carrier booking '{}': {}", carrierBookingRef, e.getMessage());
            }
        }

        // Compensation 2: Release warehouse staging slot if reserved
        if (reservedSlotId != null) {
            try {
                log.info("[Saga Compensation] Releasing warehouse slot '{}'", reservedSlotId);
                warehouseService.releaseReservation(new SlotId(reservedSlotId), shipmentId);
            } catch (Exception e) {
                log.error("[Saga Compensation] Failed to release warehouse slot '{}': {}", reservedSlotId, e.getMessage());
            }
        }

        // Finalize compensated state
        sagaState.setStatus(DispatchSagaStatus.COMPENSATED_FAILURE);
        sagaState.setCurrentStep("COMPENSATED");
        sagaState.setUpdatedAt(Instant.now());
        sagaRepository.saveAndFlush(sagaState);
        log.info("[Saga Compensation] Backward compensation completed for saga '{}'", sagaState.getSagaId());
    }
}
