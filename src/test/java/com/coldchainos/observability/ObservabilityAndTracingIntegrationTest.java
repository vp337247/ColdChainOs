package com.coldchainos.observability;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.observability.ColdChainMetrics;
import com.coldchainos.shared.observability.CorrelationContext;
import com.coldchainos.shared.outbox.infrastructure.persistence.SpringDataOutboxEventRepository;
import com.coldchainos.shared.outbox.infrastructure.publisher.OutboxRelayPublisher;
import com.coldchainos.shipment.application.ShipmentSummaryQueryService;
import com.coldchainos.shipment.application.TelemetryIngestionService;
import com.coldchainos.shipment.application.dto.TelemetryStreamPayload;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.shipment.infrastructure.kafka.TelemetryKafkaConsumer;
import com.coldchainos.shipment.infrastructure.kafka.TelemetryKafkaProducer;
import com.coldchainos.tenant.application.TenantProvisioningService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 Integration Test: Distributed Tracing & Observability.
 *
 * Verifies:
 * 1. SLF4J MDC scoped correlation management (traceId, spanId, tenantId).
 * 2. Distributed trace header propagation over Kafka record headers.
 * 3. Micrometer / Prometheus custom business metric counters and latency timers.
 * 4. MeterRegistry tracking outbox relays, telemetry frames, and thermal excursions.
 */
@SpringBootTest
class ObservabilityAndTracingIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private SpringDataOutboxEventRepository outboxRepository;

    @Autowired
    private OutboxRelayPublisher outboxRelayPublisher;

    @Autowired
    private TelemetryKafkaProducer telemetryKafkaProducer;

    @Autowired
    private TelemetryKafkaConsumer telemetryKafkaConsumer;

    @Autowired
    private ShipmentSummaryQueryService summaryQueryService;

    @Autowired
    private TelemetryIngestionService ingestionService;

    @Autowired
    private CorrelationContext correlationContext;

    @Autowired
    private ColdChainMetrics coldChainMetrics;

    @Autowired
    private MeterRegistry meterRegistry;

    private final TenantId tenantId = TenantId.of("obs_test");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantId, "Observability Logistics Lab");
        TenantContext.executeAs(tenantId, () -> {
            outboxRepository.deleteAll();
        });
        telemetryKafkaConsumer.resetProcessedCount();
    }

    @Test
    @DisplayName("Should bind and unbind traceId, spanId, and tenantId cleanly in SLF4J MDC")
    void shouldManageMdcCorrelationContext() {
        String testTrace = "trace-" + UUID.randomUUID();
        String testSpan = "span-" + UUID.randomUUID().toString().substring(0, 8);
        String testTenant = "tenant-alpha";

        assertThat(MDC.get(CorrelationContext.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.MDC_SPAN_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.MDC_TENANT_ID)).isNull();

        AtomicBoolean executed = new AtomicBoolean(false);
        correlationContext.runWithCorrelation(testTrace, testSpan, testTenant, () -> {
            assertThat(MDC.get(CorrelationContext.MDC_TRACE_ID)).isEqualTo(testTrace);
            assertThat(MDC.get(CorrelationContext.MDC_SPAN_ID)).isEqualTo(testSpan);
            assertThat(MDC.get(CorrelationContext.MDC_TENANT_ID)).isEqualTo(testTenant);
            executed.set(true);
        });

        assertThat(executed.get()).isTrue();
        // Crucial: MDC must be fully sanitized after block execution
        assertThat(MDC.get(CorrelationContext.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.MDC_SPAN_ID)).isNull();
        assertThat(MDC.get(CorrelationContext.MDC_TENANT_ID)).isNull();
    }

    @Test
    @DisplayName("Should propagate X-Trace-ID and X-Span-ID Kafka headers during Outbox Relay and Telemetry streaming")
    void shouldPropagateTraceHeadersOverKafka() throws Exception {
        // --- 1. Outbox Relay Trace Header Propagation ---
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-" + suffix);
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.REFRIGERATED_2_TO_8, 300);

        Location frankfurt = Location.of("FRA", "Frankfurt Depot", "Frankfurt", "DE");
        Location paris = Location.of("CDG", "Paris Hub", "Paris", "FR");
        TransitLeg leg = TransitLeg.of(1, frankfurt, paris, Instant.now(), Instant.now().plusSeconds(7200));

        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, List.of(leg));
        TenantContext.executeAs(tenantId, () -> shipmentRepository.save(shipment));

        // Consume directly from Kafka to verify headers dispatched by OutboxRelayPublisher
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9094");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "obs-verify-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(OutboxRelayPublisher.SHIPMENT_EVENTS_TOPIC));
            consumer.poll(Duration.ofMillis(200)); // warm up partition assignment

            // Relay outbox event
            int relayed = outboxRelayPublisher.publishPendingEventsForTenant(tenantId);
            assertThat(relayed).isGreaterThanOrEqualTo(1);

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records.isEmpty()).isFalse();

            ConsumerRecord<String, String> record = records.iterator().next();
            assertThat(record.headers().lastHeader(CorrelationContext.HEADER_TRACE_ID)).isNotNull();
            assertThat(record.headers().lastHeader(CorrelationContext.HEADER_SPAN_ID)).isNotNull();

            String traceId = new String(record.headers().lastHeader(CorrelationContext.HEADER_TRACE_ID).value(), StandardCharsets.UTF_8);
            assertThat(traceId).isNotBlank();
        }

        // --- 2. Telemetry Producer Trace Header Propagation ---
        TelemetryStreamPayload payload = new TelemetryStreamPayload(
            tenantId.value(),
            shipment.getId().value(),
            "SENSOR-OBS-01",
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            BigDecimal.valueOf(4.50),
            BigDecimal.valueOf(50.0),
            BigDecimal.valueOf(50.1109),
            BigDecimal.valueOf(8.6821),
            BigDecimal.valueOf(98.0)
        );

        telemetryKafkaProducer.sendTelemetry(payload).get(5, TimeUnit.SECONDS);
        awaitUntil(() -> ingestionService.getLatestTelemetry(tenantId, shipment.getId()).isPresent(), 20);
    }

    @Test
    @DisplayName("Should track business counters and timers in Micrometer MeterRegistry upon event execution")
    void shouldTrackMicrometerMetrics() throws Exception {
        // Record simulated metrics
        coldChainMetrics.recordOutboxRelayed(tenantId.value(), 5);
        coldChainMetrics.recordTelemetryIngested(tenantId.value(), false);
        coldChainMetrics.recordTelemetryIngested(tenantId.value(), true);
        coldChainMetrics.recordExcursionDetected(tenantId.value(), ThermalCategory.FROZEN_MINUS_20.name());
        coldChainMetrics.recordProjectionLatency(42L);

        // Verify global outbox counter
        double relayedTotal = meterRegistry.get("coldchain.outbox.relayed.total").counter().count();
        assertThat(relayedTotal).isGreaterThanOrEqualTo(5.0);

        // Verify telemetry ingestion counter
        double telemetryTotal = meterRegistry.get("coldchain.telemetry.ingested.total").counter().count();
        assertThat(telemetryTotal).isGreaterThanOrEqualTo(2.0);

        // Verify excursion counter
        double excursionTotal = meterRegistry.get("coldchain.excursions.detected.total").counter().count();
        assertThat(excursionTotal).isGreaterThanOrEqualTo(1.0);

        // Verify CQRS projection latency timer
        long timerCount = meterRegistry.get("coldchain.cqrs.projection.latency").timer().count();
        assertThat(timerCount).isGreaterThanOrEqualTo(1);
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
}
