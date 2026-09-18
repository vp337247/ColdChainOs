package com.coldchainos.shipment.telemetry;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxEventJpaEntity;
import com.coldchainos.shared.outbox.infrastructure.persistence.OutboxStatus;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import com.coldchainos.shipment.application.TelemetryIngestionService;
import com.coldchainos.shipment.application.dto.TelemetryStreamPayload;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.shipment.infrastructure.kafka.TelemetryKafkaConsumer;
import com.coldchainos.shipment.infrastructure.kafka.TelemetryKafkaProducer;
import com.coldchainos.shipment.infrastructure.persistence.TelemetryHistoryJpaEntity;
import com.coldchainos.tenant.application.TenantProvisioningService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 Integration Test: High-Throughput Telemetry Streaming with Kafka.
 *
 * Verifies:
 * 1. Partition affinity: All telemetry for a shipment routes to the exact same Kafka partition.
 * 2. End-to-End Pipeline: Raw Kafka stream -> Consumer -> Redis hot cache + PostgreSQL history.
 * 3. Excursion Handling: Streamed anomaly triggers aggregate quarantine and outbox event.
 * 4. Dead Letter Queue (DLQ): Poison pills are forwarded to DLQ without halting partition progress.
 */
@SpringBootTest
class KafkaTelemetryStreamIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private TelemetryKafkaProducer kafkaProducer;

    @Autowired
    private TelemetryKafkaConsumer kafkaConsumer;

    @Autowired
    private SpringDataOutboxEventRepository outboxRepository;

    private final TenantId tenantId = TenantId.of("telemetry_kafka");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantId, "Kafka Telemetry Logistics Inc");
        TenantContext.executeAs(tenantId, () -> {
            outboxRepository.deleteAll();
        });
        kafkaConsumer.resetProcessedCount();
    }

    @Test
    @DisplayName("Should guarantee partition affinity by routing all readings for a shipment to the same partition")
    void shouldGuaranteePartitionAffinityForShipment() throws Exception {
        Shipment shipmentA = createInTransitShipment(ThermalCategory.REFRIGERATED_2_TO_8);
        Shipment shipmentB = createInTransitShipment(ThermalCategory.FROZEN_MINUS_20);

        TenantContext.executeAs(tenantId, () -> {
            shipmentRepository.save(shipmentA);
            shipmentRepository.save(shipmentB);
        });

        // Emit 5 readings for shipment A
        Set<Integer> partitionsForA = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            TelemetryStreamPayload payloadA = new TelemetryStreamPayload(
                tenantId.value(),
                shipmentA.getId().value(),
                "SENSOR-A-" + i,
                Instant.now().plusSeconds(i).truncatedTo(ChronoUnit.SECONDS),
                BigDecimal.valueOf(4.5),
                BigDecimal.valueOf(50.0),
                BigDecimal.valueOf(52.5200),
                BigDecimal.valueOf(13.4050),
                BigDecimal.valueOf(95.0)
            );
            CompletableFuture<SendResult<String, String>> future = kafkaProducer.sendTelemetry(payloadA);
            SendResult<String, String> result = future.get(5, TimeUnit.SECONDS);
            partitionsForA.add(result.getRecordMetadata().partition());
        }

        // Emit 5 readings for shipment B
        Set<Integer> partitionsForB = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            TelemetryStreamPayload payloadB = new TelemetryStreamPayload(
                tenantId.value(),
                shipmentB.getId().value(),
                "SENSOR-B-" + i,
                Instant.now().plusSeconds(i).truncatedTo(ChronoUnit.SECONDS),
                BigDecimal.valueOf(-19.0),
                BigDecimal.valueOf(35.0),
                BigDecimal.valueOf(48.8566),
                BigDecimal.valueOf(2.3522),
                BigDecimal.valueOf(88.0)
            );
            CompletableFuture<SendResult<String, String>> future = kafkaProducer.sendTelemetry(payloadB);
            SendResult<String, String> result = future.get(5, TimeUnit.SECONDS);
            partitionsForB.add(result.getRecordMetadata().partition());
        }

        // Assert: Exactly one partition was used for all readings of shipment A
        assertThat(partitionsForA)
            .as("All readings for shipment A must land on the same Kafka partition")
            .hasSize(1);

        // Assert: Exactly one partition was used for all readings of shipment B
        assertThat(partitionsForB)
            .as("All readings for shipment B must land on the same Kafka partition")
            .hasSize(1);
    }

    @Test
    @DisplayName("Should ingest Kafka stream into Redis hot cache, PostgreSQL history, and trigger excursion quarantine")
    void shouldIngestKafkaStreamAndTriggerQuarantineOnExcursion() throws Exception {
        Shipment shipment = createInTransitShipment(ThermalCategory.REFRIGERATED_2_TO_8);
        TenantContext.executeAs(tenantId, () -> shipmentRepository.save(shipment));

        // 1. Send normal reading (+5.0 C)
        Instant time1 = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        TelemetryStreamPayload normalPayload = new TelemetryStreamPayload(
            tenantId.value(),
            shipment.getId().value(),
            "SENSOR-STREAM-01",
            time1,
            BigDecimal.valueOf(5.00),
            BigDecimal.valueOf(55.00),
            BigDecimal.valueOf(40.7128),
            BigDecimal.valueOf(-74.0060),
            BigDecimal.valueOf(99.00)
        );
        kafkaProducer.sendTelemetry(normalPayload).get(5, TimeUnit.SECONDS);

        // 2. Send excursion reading (+16.5 C)
        Instant time2 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        TelemetryStreamPayload excursionPayload = new TelemetryStreamPayload(
            tenantId.value(),
            shipment.getId().value(),
            "SENSOR-STREAM-02",
            time2,
            BigDecimal.valueOf(16.50),
            BigDecimal.valueOf(62.00),
            BigDecimal.valueOf(40.7589),
            BigDecimal.valueOf(-73.9851),
            BigDecimal.valueOf(98.00)
        );
        kafkaProducer.sendTelemetry(excursionPayload).get(5, TimeUnit.SECONDS);

        // Wait up to 20 seconds for Kafka consumer to process both records
        awaitUntil(() -> ingestionService.getHistoricalReadings(tenantId, shipment.getId()).size() >= 2, 20);

        // Assert 1: Redis has latest excursion reading
        Optional<TelemetryReading> latestInRedis = ingestionService.getLatestTelemetry(tenantId, shipment.getId());
        assertThat(latestInRedis).isPresent();
        assertThat(latestInRedis.get().temperatureCelsius()).isEqualByComparingTo("16.50");

        // Assert 2: PostgreSQL historical table has both readings recorded under tenant schema
        List<TelemetryHistoryJpaEntity> history = ingestionService.getHistoricalReadings(tenantId, shipment.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getTemperatureCelsius()).isEqualByComparingTo("16.50");
        assertThat(history.get(1).getTemperatureCelsius()).isEqualByComparingTo("5.00");

        // Assert 3: Shipment transitioned to QUARANTINED in database
        TenantContext.executeAs(tenantId, () -> {
            Shipment updated = shipmentRepository.findById(shipment.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ShipmentStatus.QUARANTINED);

            // Assert: Outbox event recorded for relay
            List<OutboxEventJpaEntity> pending = outboxRepository.findTop50ByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING);
            assertThat(pending).anyMatch(e -> "ShipmentQuarantinedEvent".equals(e.getEventType()));
        });
    }

    @Test
    @DisplayName("Should route poison pill payload to Dead Letter Queue (DLQ) without halting stream consumption")
    void shouldRoutePoisonPillToDeadLetterQueueWithoutHaltingStream() throws Exception {
        Shipment shipment = createInTransitShipment(ThermalCategory.CONTROLLED_ROOM_TEMP_15_TO_25);
        TenantContext.executeAs(tenantId, () -> shipmentRepository.save(shipment));

        // 1. Send malformed/poison JSON directly to raw telemetry topic
        String poisonPill = "{\"corrupted_binary\": \"INVALID_HEX_FF_00_MALFORMED_JSON_FRAME\"";
        kafkaProducer.sendRaw(shipment.getId().value().toString(), poisonPill).get(5, TimeUnit.SECONDS);

        // 2. Immediately send a valid reading afterwards
        long countBeforeValid = kafkaConsumer.getProcessedCount();
        Instant validTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        TelemetryStreamPayload validPayload = new TelemetryStreamPayload(
            tenantId.value(),
            shipment.getId().value(),
            "SENSOR-RESILIENT-01",
            validTime,
            BigDecimal.valueOf(20.00),
            BigDecimal.valueOf(45.00),
            BigDecimal.valueOf(37.7749),
            BigDecimal.valueOf(-122.4194),
            BigDecimal.valueOf(90.00)
        );
        kafkaProducer.sendTelemetry(validPayload).get(5, TimeUnit.SECONDS);

        // Wait for the valid record to be consumed
        awaitUntil(() -> ingestionService.getLatestTelemetry(tenantId, shipment.getId())
            .filter(r -> r.temperatureCelsius().compareTo(BigDecimal.valueOf(20.00)) == 0).isPresent(), 20);

        // Assert: Valid message was processed despite the preceding poison pill (no head-of-line blocking!)
        Optional<TelemetryReading> cached = ingestionService.getLatestTelemetry(tenantId, shipment.getId());
        assertThat(cached).isPresent();
        assertThat(cached.get().temperatureCelsius()).isEqualByComparingTo("20.00");

        // Assert: The poison pill was routed to the DLQ topic
        Properties dlqProps = new Properties();
        dlqProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9094");
        dlqProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-verification-group-" + UUID.randomUUID());
        dlqProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        dlqProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        dlqProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> dlqConsumer = new KafkaConsumer<>(dlqProps)) {
            dlqConsumer.subscribe(List.of("coldchain.telemetry.dlq"));
            ConsumerRecords<String, String> dlqRecords = dlqConsumer.poll(Duration.ofSeconds(10));
            assertThat(dlqRecords.count()).isGreaterThanOrEqualTo(1);
            assertThat(dlqRecords.iterator().next().value()).contains("INVALID_HEX_FF_00_MALFORMED_JSON_FRAME");
        }
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        assertThat(condition.getAsBoolean()).as("Condition was not satisfied within " + timeoutSeconds + "s").isTrue();
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
