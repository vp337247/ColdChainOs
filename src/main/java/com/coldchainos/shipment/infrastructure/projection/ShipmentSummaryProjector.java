package com.coldchainos.shipment.infrastructure.projection;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.idempotency.application.IdempotentConsumerExecutor;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shipment.domain.Shipment;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.ShipmentRepository;
import com.coldchainos.shipment.domain.TransitLeg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CQRS Read-Side Projector.
 * Consumes domain events from Kafka and asynchronously maintains the de-normalized
 * shipment_summary_view read model in PostgreSQL.
 * Deduplicated via IdempotentConsumerExecutor to ensure projection consistency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentSummaryProjector {

    public static final String CONSUMER_NAME = "ShipmentSummaryProjector";

    private final ShipmentSummaryViewJpaRepository summaryRepository;
    private final ShipmentRepository shipmentRepository;
    private final IdempotentConsumerExecutor idempotentExecutor;
    private final ObjectMapper objectMapper;
    private final com.coldchainos.shared.observability.CorrelationContext correlationContext;
    private final com.coldchainos.shared.observability.ColdChainMetrics coldChainMetrics;

    private final AtomicInteger projectedCount = new AtomicInteger(0);

    @KafkaListener(
        topics = "coldchain.shipment.events",
        groupId = "coldchain-cqrs-summary-projector"
    )
    public void onShipmentEvent(ConsumerRecord<String, String> record) {
        String tenantIdStr = extractHeader(record, "X-Tenant-ID");
        String eventType = extractHeader(record, "X-Event-Type");
        String eventIdStr = extractHeader(record, "X-Event-ID");
        String traceId = extractHeader(record, com.coldchainos.shared.observability.CorrelationContext.HEADER_TRACE_ID);
        String spanId = extractHeader(record, com.coldchainos.shared.observability.CorrelationContext.HEADER_SPAN_ID);

        if (tenantIdStr == null || eventIdStr == null || eventType == null) {
            log.warn("Projector skipping malformed event without required headers on partition {} offset {}",
                record.partition(), record.offset());
            return;
        }

        TenantId tenantId = new TenantId(tenantIdStr);
        UUID eventId = UUID.fromString(eventIdStr);
        UUID shipmentUuid = UUID.fromString(record.key());

        correlationContext.runWithCorrelation(traceId, spanId, tenantIdStr, () -> {
            TenantContext.executeAs(tenantId, () -> {
                idempotentExecutor.executeIdempotently(
                    CONSUMER_NAME,
                    eventId,
                    eventType,
                    shipmentUuid.toString(),
                    () -> {
                        io.micrometer.core.instrument.Timer.Sample sample = coldChainMetrics.startTimer();
                        try {
                            applyEventProjection(tenantId, shipmentUuid, eventType, eventId, record.value());
                            projectedCount.incrementAndGet();
                        } catch (Exception e) {
                            log.error("Failed to project event '{}' for shipment '{}': {}",
                                eventType, shipmentUuid, e.getMessage(), e);
                            throw new RuntimeException("Projection failed", e);
                        } finally {
                            coldChainMetrics.stopTimer(sample);
                        }
                    }
                );
            });
        });
    }

    private void applyEventProjection(TenantId tenantId, UUID shipmentUuid, String eventType, UUID eventId, String payloadJson) throws Exception {
        JsonNode json = objectMapper.readTree(payloadJson);
        Instant occurredAt = json.has("occurredAt")
            ? Instant.parse(json.get("occurredAt").asText())
            : Instant.now();

        switch (eventType) {
            case "ShipmentCreatedEvent" -> projectCreatedEvent(tenantId, shipmentUuid, json, eventType, occurredAt);
            case "CarrierAssignedEvent" -> projectCarrierAssigned(shipmentUuid, json, eventType, occurredAt);
            case "ShipmentQuarantinedEvent" -> projectQuarantined(shipmentUuid, eventType, occurredAt);
            case "ShipmentQuarantineReleasedEvent" -> projectQuarantineReleased(shipmentUuid, eventType, occurredAt);
            case "ShipmentDeliveredEvent" -> projectDelivered(shipmentUuid, eventType, occurredAt);
            case "ShipmentCancelledEvent" -> projectCancelled(shipmentUuid, eventType, occurredAt);
            case "CustodyTransferredEvent" -> projectCustodyTransferred(shipmentUuid, eventType, occurredAt);
            default -> log.debug("No specific projection rule for event type '{}'; skipping field updates", eventType);
        }
    }

    private void projectCreatedEvent(TenantId tenantId, UUID shipmentUuid, JsonNode json, String eventType, Instant occurredAt) {
        String trackingNumber = json.has("trackingNumber") && json.get("trackingNumber").has("value")
            ? json.get("trackingNumber").get("value").asText()
            : "UNKNOWN";

        BigDecimal minTemp = BigDecimal.valueOf(2.0);
        BigDecimal maxTemp = BigDecimal.valueOf(8.0);
        String thermalCategory = "REFRIGERATED_2_TO_8";

        if (json.has("threshold")) {
            JsonNode th = json.get("threshold");
            if (th.has("minCelsius")) minTemp = new BigDecimal(th.get("minCelsius").asText());
            if (th.has("maxCelsius")) maxTemp = new BigDecimal(th.get("maxCelsius").asText());
            if (th.has("category")) thermalCategory = th.get("category").asText();
        }

        String originCode = "ORIGIN";
        String originCity = "Origin Hub";
        String destCode = "DEST";
        String destCity = "Destination Hub";
        int totalLegs = 1;

        // Enrich with transit leg details from write aggregate if available
        Optional<Shipment> shipmentOpt = shipmentRepository.findById(new ShipmentId(shipmentUuid));
        if (shipmentOpt.isPresent()) {
            Shipment shipment = shipmentOpt.get();
            if (!shipment.getLegs().isEmpty()) {
                TransitLeg firstLeg = shipment.getLegs().get(0);
                originCode = firstLeg.getOrigin().code();
                originCity = firstLeg.getOrigin().city();

                TransitLeg lastLeg = shipment.getLegs().get(shipment.getLegs().size() - 1);
                destCode = lastLeg.getDestination().code();
                destCity = lastLeg.getDestination().city();
                totalLegs = shipment.getLegs().size();
            }
        }

        ShipmentSummaryViewJpaEntity entity = new ShipmentSummaryViewJpaEntity(
            shipmentUuid,
            tenantId.value(),
            trackingNumber,
            "CREATED",
            thermalCategory,
            originCode,
            originCity,
            destCode,
            destCity,
            null,
            minTemp,
            maxTemp,
            totalLegs,
            false,
            occurredAt,
            null,
            eventType,
            occurredAt,
            Instant.now()
        );

        summaryRepository.saveAndFlush(entity);
        log.info("PROJECTED new shipment summary view for '{}' (Tracking: {})", shipmentUuid, trackingNumber);
    }

    private void projectCarrierAssigned(UUID shipmentUuid, JsonNode json, String eventType, Instant occurredAt) {
        summaryRepository.findById(shipmentUuid).ifPresent(entity -> {
            String carrierId = "CARRIER-ASSIGNED";
            if (json.has("carrierId") && json.get("carrierId").has("value")) {
                carrierId = json.get("carrierId").get("value").asText();
            }
            entity.setAssignedCarrierId(carrierId);
            entity.setLastEventType(eventType);
            entity.setLastEventTimestamp(occurredAt);
            entity.setProjectionUpdatedAt(Instant.now());
            summaryRepository.saveAndFlush(entity);
            log.info("PROJECTED carrier '{}' assigned to shipment '{}'", carrierId, shipmentUuid);
        });
    }

    private void projectQuarantined(UUID shipmentUuid, String eventType, Instant occurredAt) {
        summaryRepository.findById(shipmentUuid).ifPresent(entity -> {
            entity.setStatus("QUARANTINED");
            entity.setIsQuarantined(true);
            entity.setLastEventType(eventType);
            entity.setLastEventTimestamp(occurredAt);
            entity.setProjectionUpdatedAt(Instant.now());
            summaryRepository.saveAndFlush(entity);
            log.info("PROJECTED QUARANTINED status for shipment '{}'", shipmentUuid);
        });
    }

    private void projectQuarantineReleased(UUID shipmentUuid, String eventType, Instant occurredAt) {
        summaryRepository.findById(shipmentUuid).ifPresent(entity -> {
            entity.setStatus("IN_TRANSIT");
            entity.setIsQuarantined(false);
            entity.setLastEventType(eventType);
            entity.setLastEventTimestamp(occurredAt);
            entity.setProjectionUpdatedAt(Instant.now());
            summaryRepository.saveAndFlush(entity);
            log.info("PROJECTED quarantine release for shipment '{}'", shipmentUuid);
        });
    }

    private void projectDelivered(UUID shipmentUuid, String eventType, Instant occurredAt) {
        summaryRepository.findById(shipmentUuid).ifPresent(entity -> {
            entity.setStatus("DELIVERED");
            entity.setDeliveredAt(occurredAt);
            entity.setLastEventType(eventType);
            entity.setLastEventTimestamp(occurredAt);
            entity.setProjectionUpdatedAt(Instant.now());
            summaryRepository.saveAndFlush(entity);
            log.info("PROJECTED DELIVERED status for shipment '{}'", shipmentUuid);
        });
    }

    private void projectCancelled(UUID shipmentUuid, String eventType, Instant occurredAt) {
        summaryRepository.findById(shipmentUuid).ifPresent(entity -> {
            entity.setStatus("CANCELLED");
            entity.setLastEventType(eventType);
            entity.setLastEventTimestamp(occurredAt);
            entity.setProjectionUpdatedAt(Instant.now());
            summaryRepository.saveAndFlush(entity);
            log.info("PROJECTED CANCELLED status for shipment '{}'", shipmentUuid);
        });
    }

    private void projectCustodyTransferred(UUID shipmentUuid, String eventType, Instant occurredAt) {
        summaryRepository.findById(shipmentUuid).ifPresent(entity -> {
            entity.setStatus("IN_TRANSIT");
            entity.setLastEventType(eventType);
            entity.setLastEventTimestamp(occurredAt);
            entity.setProjectionUpdatedAt(Instant.now());
            summaryRepository.saveAndFlush(entity);
            log.info("PROJECTED IN_TRANSIT custody transfer for shipment '{}'", shipmentUuid);
        });
    }

    private String extractHeader(ConsumerRecord<String, String> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }

    public int getProjectedCount() {
        return projectedCount.get();
    }

    public void resetProjectedCount() {
        projectedCount.set(0);
    }
}
