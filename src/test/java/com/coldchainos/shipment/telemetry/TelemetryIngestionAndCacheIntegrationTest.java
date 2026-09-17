package com.coldchainos.shipment.telemetry;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxEventJpaEntity;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxStatus;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import com.coldchainos.shipment.application.TelemetryIngestionService;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.tenant.application.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying Redis distributed hot caching, atomic IoT sensor packet deduplication,
 * and automated aggregate quarantine upon temperature excursion detection.
 */
@SpringBootTest
class TelemetryIngestionAndCacheIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private SpringDataOutboxEventRepository outboxRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final TenantId tenantId = TenantId.of("telemetry_test");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantId, "Telemetry Testing Corp");
        TenantContext.executeAs(tenantId, () -> {
            outboxRepository.deleteAll();
        });
    }

    @Test
    @DisplayName("Should cache latest telemetry snapshot in Redis and retrieve with high precision")
    void shouldCacheAndRetrieveLatestTelemetrySnapshotInRedis() {
        // Arrange
        Shipment shipment = createInTransitShipment(ThermalCategory.REFRIGERATED_2_TO_8);
        TenantContext.executeAs(tenantId, () -> shipmentRepository.save(shipment));

        SensorId sensorId = SensorId.of("BEACON-BLE-0091");
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        GeoCoordinates coords = GeoCoordinates.of(40.7128, -74.0060);
        TelemetryReading reading = TelemetryReading.of(
            sensorId,
            recordedAt,
            BigDecimal.valueOf(4.55),
            BigDecimal.valueOf(55.20),
            coords,
            BigDecimal.valueOf(98.50)
        );

        // Act: Ingest telemetry
        boolean processed = ingestionService.ingestTelemetry(tenantId, shipment.getId(), reading);
        assertThat(processed).isTrue();

        // Assert: Read back from Redis cache
        Optional<TelemetryReading> cachedOpt = ingestionService.getLatestTelemetry(tenantId, shipment.getId());
        assertThat(cachedOpt).isPresent();

        TelemetryReading cached = cachedOpt.get();
        assertThat(cached.sensorId()).isEqualTo(sensorId);
        assertThat(cached.recordedAt()).isEqualTo(recordedAt);
        assertThat(cached.temperatureCelsius()).isEqualByComparingTo("4.55");
        assertThat(cached.humidityPercentage()).isEqualByComparingTo("55.20");
        assertThat(cached.coordinates().latitude()).isEqualByComparingTo("40.712800");
        assertThat(cached.coordinates().longitude()).isEqualByComparingTo("-74.006000");
        assertThat(cached.batteryLevelPercentage()).isEqualByComparingTo("98.50");
    }

    @Test
    @DisplayName("Should atomically deduplicate identical IoT sensor packets using Redis SETNX")
    void shouldAtomicallyDeduplicateDuplicateSensorPackets() {
        Shipment shipment = createInTransitShipment(ThermalCategory.FROZEN_MINUS_20);
        TenantContext.executeAs(tenantId, () -> shipmentRepository.save(shipment));

        SensorId sensorId = SensorId.of("BEACON-FROZEN-881");
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        TelemetryReading reading = TelemetryReading.of(
            sensorId,
            recordedAt,
            BigDecimal.valueOf(-18.50),
            BigDecimal.valueOf(30.00),
            GeoCoordinates.of(48.8566, 2.3522),
            BigDecimal.valueOf(85.00)
        );

        // First attempt: should succeed
        boolean firstAttempt = ingestionService.ingestTelemetry(tenantId, shipment.getId(), reading);
        assertThat(firstAttempt).isTrue();

        // Second attempt (simulating cellular network packet retry): must be discarded!
        boolean secondAttempt = ingestionService.ingestTelemetry(tenantId, shipment.getId(), reading);
        assertThat(secondAttempt).as("Duplicate sensor reading must be discarded").isFalse();
    }

    @Test
    @DisplayName("Should detect temperature excursion during ingestion and trigger aggregate quarantine + outbox event")
    void shouldDetectExcursionAndTriggerQuarantine() {
        // Arrange: Refrigerated shipment (+2 to +8 C)
        Shipment shipment = createInTransitShipment(ThermalCategory.REFRIGERATED_2_TO_8);
        TenantContext.executeAs(tenantId, () -> shipmentRepository.save(shipment));

        // Act: Sensor reports +14.2 C (severe excursion!)
        SensorId sensorId = SensorId.of("BEACON-EXCURSION-01");
        Instant recordedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        TelemetryReading excursionReading = TelemetryReading.of(
            sensorId,
            recordedAt,
            BigDecimal.valueOf(14.20),
            BigDecimal.valueOf(60.00),
            GeoCoordinates.of(51.5074, -0.1278),
            BigDecimal.valueOf(92.00)
        );

        boolean processed = ingestionService.ingestTelemetry(tenantId, shipment.getId(), excursionReading);
        assertThat(processed).isTrue();

        // Assert: Aggregate is now quarantined in PostgreSQL
        TenantContext.executeAs(tenantId, () -> {
            Shipment updated = shipmentRepository.findById(shipment.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ShipmentStatus.QUARANTINED);
            assertThat(updated.getPreQuarantineStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);

            // Assert: Outbox event was recorded atomically
            List<OutboxEventJpaEntity> pending = outboxRepository.findTop50ByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING);
            assertThat(pending).anyMatch(e -> "ShipmentQuarantinedEvent".equals(e.getEventType()));
        });
    }

    private Shipment createInTransitShipment(ThermalCategory category) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-" + suffix);
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(category, 300);

        Location origin = Location.of("FRA", "Frankfurt Depot", "Frankfurt", "DE");
        Location dest = Location.of("CDG", "Paris Hub", "Paris", "FR");
        TransitLeg leg = TransitLeg.of(1, origin, dest, Instant.now(), Instant.now().plusSeconds(7200));

        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, List.of(leg));
        shipment.assignCarrier(leg.getId(), CarrierId.of("CARRIER-EXPRESS"));
        shipment.startTransit();
        return shipment;
    }
}
