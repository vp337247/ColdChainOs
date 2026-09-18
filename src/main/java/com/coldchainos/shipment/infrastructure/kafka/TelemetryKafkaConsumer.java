package com.coldchainos.shipment.infrastructure.kafka;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.application.TelemetryIngestionService;
import com.coldchainos.shipment.application.dto.TelemetryStreamPayload;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.TelemetryReading;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-concurrency Kafka consumer for IoT telemetry readings.
 * Consumes partitioned telemetry streams, deserializes payloads, enforces tenant context,
 * and delegates to TelemetryIngestionService for deduplication, Redis caching,
 * PostgreSQL historical persistence, and temperature excursion enforcement.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryKafkaConsumer {

    private final TelemetryIngestionService telemetryIngestionService;
    private final ObjectMapper objectMapper;

    private final AtomicLong processedCount = new AtomicLong(0);

    @KafkaListener(
        topics = "${coldchainos.telemetry.raw-topic:coldchain.telemetry.raw}",
        containerFactory = "telemetryKafkaListenerContainerFactory"
    )
    public void consumeTelemetry(ConsumerRecord<String, String> record) throws Exception {
        log.debug("Received telemetry record on topic '{}' partition {} offset {}: key={}",
            record.topic(), record.partition(), record.offset(), record.key());

        // 1. Deserialize payload (throws JsonParseException/JsonMappingException if malformed -> routed to DLQ)
        TelemetryStreamPayload payload = objectMapper.readValue(record.value(), TelemetryStreamPayload.class);

        // 2. Resolve TenantId from header or payload
        String tenantIdStr = null;
        Header tenantHeader = record.headers().lastHeader("X-Tenant-ID");
        if (tenantHeader != null && tenantHeader.value() != null) {
            tenantIdStr = new String(tenantHeader.value(), StandardCharsets.UTF_8);
        }
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            tenantIdStr = payload.tenantId();
        }
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new IllegalArgumentException("Telemetry frame rejected: missing TenantId in header or payload");
        }

        TenantId tenantId = new TenantId(tenantIdStr);
        ShipmentId shipmentId = payload.toShipmentId();
        TelemetryReading reading = payload.toDomainReading();

        // 3. Delegate to domain ingestion pipeline
        boolean ingested = telemetryIngestionService.ingestTelemetry(tenantId, shipmentId, reading);
        processedCount.incrementAndGet();

        if (ingested) {
            log.info("Ingested telemetry for shipment '{}' (temp: {} C, partition: {}, offset: {})",
                shipmentId, reading.temperatureCelsius(), record.partition(), record.offset());
        } else {
            log.debug("Discarded duplicate telemetry for sensor '{}' at '{}'",
                reading.sensorId(), reading.recordedAt());
        }
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public void resetProcessedCount() {
        processedCount.set(0);
    }
}
