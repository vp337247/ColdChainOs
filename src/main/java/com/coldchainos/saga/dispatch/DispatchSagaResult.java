package com.coldchainos.saga.dispatch;

import java.util.UUID;

/**
 * Result returned upon forward completion or backward compensation of a dispatch saga.
 *
 * @param sagaId            saga execution identifier
 * @param shipmentId        shipment identifier
 * @param status            final saga status (COMPLETED or COMPENSATED_FAILURE)
 * @param warehouseSlotId   allocated slot identifier
 * @param carrierBookingId  carrier tracking / confirmation reference
 * @param isCompensated     true if compensation rollback occurred
 * @param message           diagnostic or failure rationale
 */
public record DispatchSagaResult(
    UUID sagaId,
    UUID shipmentId,
    DispatchSagaStatus status,
    UUID warehouseSlotId,
    String carrierBookingId,
    boolean isCompensated,
    String message
) {}
