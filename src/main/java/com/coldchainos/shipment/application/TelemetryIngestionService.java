package com.coldchainos.shipment.application;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.shipment.infrastructure.cache.TelemetryCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * High-throughput application service for ingesting real-time IoT sensor telemetry packets.
 * Coordinates Redis deduplication, hot-state caching, and aggregate invariant enforcement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryIngestionService {

    private final TelemetryCacheRepository cacheRepository;
    private final ShipmentRepository shipmentRepository;
    private final com.coldchainos.shipment.infrastructure.persistence.TelemetryHistoryJpaRepository telemetryHistoryJpaRepository;
    private final com.coldchainos.shared.observability.ColdChainMetrics coldChainMetrics;

    /**
     * Ingests an IoT sensor telemetry packet:
     * 1. Deduplicates packet via atomic Redis SETNX. Discards duplicates cleanly.
     * 2. Writes the snapshot into Redis hot cache.
     * 3. Appends historical point to PostgreSQL time-series history.
     * 4. Evaluates shipment temperature threshold. If excursion detected, triggers aggregate quarantine.
     *
     * @return true if packet was processed; false if discarded as duplicate
     */
    public boolean ingestTelemetry(TenantId tenantId, ShipmentId shipmentId, TelemetryReading reading) {
        // 1. Atomic deduplication check in Redis
        boolean isUnique = cacheRepository.acquireDeduplicationLock(
            reading.sensorId(),
            reading.recordedAt(),
            TelemetryCacheRepository.DEFAULT_DEDUP_TTL
        );
        if (!isUnique) {
            return false;
        }

        // 2. Cache hot snapshot in Redis
        cacheRepository.cacheLatestReading(tenantId, shipmentId, reading, TelemetryCacheRepository.DEFAULT_SNAPSHOT_TTL);

        // 3. Persist historical telemetry record and evaluate threshold against shipment aggregate
        TenantContext.executeAs(tenantId, () -> {
            telemetryHistoryJpaRepository.save(new com.coldchainos.shipment.infrastructure.persistence.TelemetryHistoryJpaEntity(
                UUID.randomUUID(),
                shipmentId.value(),
                reading.sensorId().value(),
                reading.recordedAt(),
                reading.temperatureCelsius(),
                reading.humidityPercentage(),
                reading.coordinates() != null ? reading.coordinates().latitude() : null,
                reading.coordinates() != null ? reading.coordinates().longitude() : null,
                reading.batteryLevelPercentage(),
                java.time.Instant.now()
            ));

            Optional<Shipment> shipmentOpt = shipmentRepository.findById(shipmentId);
            if (shipmentOpt.isPresent()) {
                Shipment shipment = shipmentOpt.get();
                if (shipment.getStatus() == ShipmentStatus.IN_TRANSIT || shipment.getStatus() == ShipmentStatus.AT_PORT || shipment.getStatus() == ShipmentStatus.WAREHOUSE) {
                    if (shipment.getThreshold().isExcursion(reading.temperatureCelsius())) {
                        log.warn("Temperature excursion detected for shipment '{}': {} C outside [{}, {}] C",
                            shipmentId, reading.temperatureCelsius(),
                            shipment.getThreshold().minCelsius(), shipment.getThreshold().maxCelsius());

                        shipment.applyQuarantine(
                            UUID.randomUUID(),
                            "Temperature excursion recorded: " + reading.temperatureCelsius() + " C at " + reading.recordedAt()
                        );
                        shipmentRepository.save(shipment);
                        coldChainMetrics.recordExcursionDetected(tenantId.value(), shipment.getThreshold().category().name());
                        coldChainMetrics.recordTelemetryIngested(tenantId.value(), true);
                        return;
                    }
                }
            }
            coldChainMetrics.recordTelemetryIngested(tenantId.value(), false);
        });

        return true;
    }

    /**
     * Retrieves the latest telemetry reading from the in-memory Redis cache.
     */
    public Optional<TelemetryReading> getLatestTelemetry(TenantId tenantId, ShipmentId shipmentId) {
        return cacheRepository.getLatestReading(tenantId, shipmentId);
    }

    /**
     * Retrieves historical telemetry readings from PostgreSQL under active tenant schema.
     */
    public java.util.List<com.coldchainos.shipment.infrastructure.persistence.TelemetryHistoryJpaEntity> getHistoricalReadings(TenantId tenantId, ShipmentId shipmentId) {
        return TenantContext.executeAs(tenantId, () ->
            telemetryHistoryJpaRepository.findByShipmentIdOrderByRecordedAtDesc(shipmentId.value())
        );
    }
}
