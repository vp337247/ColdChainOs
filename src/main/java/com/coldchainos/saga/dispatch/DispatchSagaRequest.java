package com.coldchainos.saga.dispatch;

import com.coldchainos.shared.domain.TenantId;

import java.util.UUID;

/**
 * Command payload to initiate a shipment dispatch distributed saga.
 *
 * @param sagaId          unique tracking identifier for the saga execution
 * @param tenantId        tenant boundary context
 * @param shipmentId      shipment aggregate root identifier
 * @param warehouseSlotId warehouse bay / dock slot identifier to stage and reserve
 * @param carrierId       contracted reefer carrier identifier
 * @param operatorName    authorizing logistics operator
 */
public record DispatchSagaRequest(
    UUID sagaId,
    TenantId tenantId,
    UUID shipmentId,
    UUID warehouseSlotId,
    String carrierId,
    String operatorName
) {}
