package com.coldchainos.ai.agent;

import com.coldchainos.ai.sop.SopKnowledgeService;
import com.coldchainos.audit.application.AuditLedgerService;
import com.coldchainos.audit.infrastructure.AuditLedgerEntryJpaEntity;
import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shipment.application.ShipmentSummaryQueryService;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.TelemetryReading;
import com.coldchainos.shipment.infrastructure.cache.TelemetryCacheRepository;
import com.coldchainos.shipment.infrastructure.persistence.TelemetryHistoryJpaEntity;
import com.coldchainos.shipment.infrastructure.persistence.TelemetryHistoryJpaRepository;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryViewJpaEntity;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic domain tools provided to the Gemini AI Incident Commander.
 * Enables live querying of shipment state, telemetry history, audit ledgers, and SOP stability rules.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentInvestigationTools {

    private final ShipmentSummaryQueryService queryService;
    private final TelemetryCacheRepository cacheRepository;
    private final TelemetryHistoryJpaRepository historyRepository;
    private final AuditLedgerService auditLedgerService;
    private final SopKnowledgeService sopKnowledgeService;

    @Tool("Fetches current shipment details, origin/destination, carrier, thermal category, and status")
    public String getShipmentDetails(String shipmentIdStr) {
        log.info("[AI Tool] Fetching shipment details for ID: {}", shipmentIdStr);
        try {
            UUID shipmentId = UUID.fromString(shipmentIdStr);
            TenantId tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : TenantId.of("demo_tenant");
            Optional<ShipmentSummaryViewJpaEntity> summary = queryService.getById(tenantId, shipmentId);

            if (summary.isPresent()) {
                ShipmentSummaryViewJpaEntity s = summary.get();
                return String.format(
                    "Shipment ID: %s | Tracking: %s | Status: %s | Quarantined: %s | Category: %s | " +
                    "Carrier: %s | Route: %s -> %s | Range: [%s°C to %s°C]",
                    s.getShipmentId(), s.getTrackingNumber(), s.getStatus(), s.getIsQuarantined(),
                    s.getThermalCategory(), s.getAssignedCarrierId(), s.getOriginCode(),
                    s.getDestinationCode(), s.getMinTemperature(), s.getMaxTemperature()
                );
            } else {
                return "Shipment not found for ID: " + shipmentIdStr;
            }
        } catch (Exception e) {
            return "Error retrieving shipment: " + e.getMessage();
        }
    }

    @Tool("Retrieves latest telemetry reading and historical sensor records for excursion duration calculation")
    public String getTelemetryData(String shipmentIdStr) {
        log.info("[AI Tool] Fetching telemetry data for ID: {}", shipmentIdStr);
        try {
            UUID shipmentId = UUID.fromString(shipmentIdStr);
            TenantId tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : TenantId.of("demo_tenant");

            // 1. Check hot cache
            Optional<TelemetryReading> hotReading = cacheRepository.getLatestReading(tenantId, new ShipmentId(shipmentId));
            StringBuilder sb = new StringBuilder();

            if (hotReading.isPresent()) {
                TelemetryReading r = hotReading.get();
                sb.append(String.format("Latest Hot Telemetry: Temp=%.2f°C, Humidity=%.1f%%, Sensor=%s, Time=%s\n",
                    r.temperatureCelsius(), r.humidityPercentage(), r.sensorId().value(), r.recordedAt()));
            }

            // 2. Check time-series history
            List<TelemetryHistoryJpaEntity> history = TenantContext.executeAs(tenantId, () ->
                historyRepository.findByShipmentIdOrderByRecordedAtDesc(shipmentId)
            );

            sb.append("Recorded Sensor History Count: ").append(history.size()).append(" readings\n");
            for (int i = 0; i < Math.min(5, history.size()); i++) {
                TelemetryHistoryJpaEntity h = history.get(i);
                sb.append(String.format("  - #%d: Temp=%.2f°C at %s (Sensor=%s, Battery=%.0f%%)\n",
                    i + 1, h.getTemperatureCelsius(), h.getRecordedAt(), h.getSensorId(), h.getBatteryLevel()));
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error retrieving telemetry: " + e.getMessage();
        }
    }

    @Tool("Searches pharmaceutical Standard Operating Procedures (SOPs) and stability tolerance limits")
    public String searchSopStabilityGuidelines(String query) {
        log.info("[AI Tool] Searching SOP stability guidelines for query: {}", query);
        List<String> matches = sopKnowledgeService.searchRelevantSops(query, 3);
        if (matches.isEmpty()) {
            return "No matching SOP policies found for query: " + query;
        }
        return String.join("\n\n", matches);
    }

    @Tool("Fetches the FDA 21 CFR Part 11 cryptographic audit ledger for the shipment")
    public String getCryptographicAuditTrail(String shipmentIdStr) {
        log.info("[AI Tool] Fetching cryptographic audit ledger for ID: {}", shipmentIdStr);
        try {
            UUID shipmentId = UUID.fromString(shipmentIdStr);
            TenantId tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : TenantId.of("demo_tenant");

            List<AuditLedgerEntryJpaEntity> trail = auditLedgerService.getAuditTrail(tenantId, shipmentId);
            if (trail.isEmpty()) {
                return "No audit ledger records found for shipment: " + shipmentIdStr;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Total Cryptographic Audit Blocks: ").append(trail.size()).append("\n");
            for (AuditLedgerEntryJpaEntity entry : trail) {
                sb.append(String.format("  - Block #%d: Event=%s, Performer=%s, Hash=%s, Time=%s\n",
                    entry.getSequenceNumber(), entry.getEventType(), entry.getPerformedBy(),
                    entry.getHash().substring(0, 16) + "...", entry.getCreatedAt()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error retrieving audit trail: " + e.getMessage();
        }
    }
}
