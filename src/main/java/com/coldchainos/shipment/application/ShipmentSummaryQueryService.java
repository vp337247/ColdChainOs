package com.coldchainos.shipment.application;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryViewJpaEntity;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryViewJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CQRS Read-Side Query Service.
 * Serves UI dashboards, carrier portals, and audit search APIs with fast, non-locking
 * single-table queries against shipment_summary_view.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentSummaryQueryService {

    private final ShipmentSummaryViewJpaRepository summaryRepository;

    public List<ShipmentSummaryViewJpaEntity> getAllShipments(TenantId tenantId) {
        return TenantContext.executeAs(tenantId, () -> summaryRepository.findAll());
    }

    public List<ShipmentSummaryViewJpaEntity> getQuarantinedShipments(TenantId tenantId) {
        return TenantContext.executeAs(tenantId, () -> summaryRepository.findByIsQuarantinedTrue());
    }

    public List<ShipmentSummaryViewJpaEntity> getShipmentsByCarrier(TenantId tenantId, String carrierId) {
        return TenantContext.executeAs(tenantId, () -> summaryRepository.findByAssignedCarrierId(carrierId));
    }

    public List<ShipmentSummaryViewJpaEntity> getShipmentsByStatus(TenantId tenantId, String status) {
        return TenantContext.executeAs(tenantId, () -> summaryRepository.findByStatus(status));
    }

    public Optional<ShipmentSummaryViewJpaEntity> getByTrackingNumber(TenantId tenantId, String trackingNumber) {
        return TenantContext.executeAs(tenantId, () -> summaryRepository.findByTrackingNumber(trackingNumber));
    }

    public Optional<ShipmentSummaryViewJpaEntity> getById(TenantId tenantId, UUID shipmentId) {
        return TenantContext.executeAs(tenantId, () -> summaryRepository.findById(shipmentId));
    }
}
