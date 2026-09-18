package com.coldchainos.shipment.interfaces.rest;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.application.ShipmentSummaryQueryService;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryViewJpaEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Secured multi-tenant REST controller demonstrating RBAC method security and
 * cross-tenant defense-in-depth isolation.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
public class ShipmentSecuredController {

    private final ShipmentSummaryQueryService queryService;

    /**
     * Read summary view: Permitted for TENANT_ADMIN, LOGISTICS_OPERATOR, and AUDITOR.
     * Enforces that the caller's JWT tenant strictly matches the path tenantId.
     */
    @GetMapping("/{tenantId}/summary/{shipmentId}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'LOGISTICS_OPERATOR', 'AUDITOR') and @tenantSecurityEvaluator.isTenant(#tenantId)")
    public ResponseEntity<ShipmentSummaryViewJpaEntity> getSummary(
        @PathVariable String tenantId,
        @PathVariable UUID shipmentId
    ) {
        return queryService.getById(new TenantId(tenantId), shipmentId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Dispatch mutation: Permitted strictly for TENANT_ADMIN and LOGISTICS_OPERATOR.
     * Denied for AUDITOR (Read-only role).
     * Enforces that the caller's JWT tenant strictly matches the path tenantId.
     */
    @PostMapping("/{tenantId}/dispatch")
    @PreAuthorize("(hasRole('TENANT_ADMIN') or hasRole('LOGISTICS_OPERATOR')) and @tenantSecurityEvaluator.isTenant(#tenantId)")
    public ResponseEntity<Map<String, String>> dispatchShipment(
        @PathVariable String tenantId,
        @RequestBody Map<String, String> payload
    ) {
        return ResponseEntity.ok(Map.of(
            "status", "DISPATCHED",
            "tenantId", tenantId,
            "message", "Shipment dispatch authorized"
        ));
    }
}
