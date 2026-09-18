package com.coldchainos.shipment.infrastructure.kafka;

import com.coldchainos.shipment.application.dto.TelemetryStreamPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * High-throughput producer for streaming IoT sensor telemetry into Apache Kafka.
 * Guarantees partition affinity by using shipmentId as the partition key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final com.coldchainos.shared.observability.CorrelationContext correlationContext;

    @Value("${coldchainos.telemetry.raw-topic:coldchain.telemetry.raw}")
    private String rawTopic;

    /**
     * Publishes a validated telemetry packet to the raw telemetry topic.
     * The record key is strictly the shipmentId, ensuring all telemetry for a given
     * shipment lands on the same Kafka partition and preserves temporal ordering.
     */
    public CompletableFuture<SendResult<String, String>> sendTelemetry(TelemetryStreamPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            String partitionKey = payload.shipmentId().toString();

            ProducerRecord<String, String> record = new ProducerRecord<>(rawTopic, partitionKey, json);
            if (payload.tenantId() != null) {
                record.headers().add(new RecordHeader("X-Tenant-ID", payload.tenantId().getBytes(StandardCharsets.UTF_8)));
            }
            if (payload.sensorId() != null) {
                record.headers().add(new RecordHeader("X-Sensor-ID", payload.sensorId().getBytes(StandardCharsets.UTF_8)));
            }
            record.headers().add(new RecordHeader("X-Produced-At", Instant.now().toString().getBytes(StandardCharsets.UTF_8)));

            // Distributed Tracing: propagate active or generated trace & span IDs
            String traceId = correlationContext.getOrCreateTraceId();
            String spanId = correlationContext.getOrCreateSpanId();
            record.headers().add(new RecordHeader(com.coldchainos.shared.observability.CorrelationContext.HEADER_TRACE_ID, traceId.getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader(com.coldchainos.shared.observability.CorrelationContext.HEADER_SPAN_ID, spanId.getBytes(StandardCharsets.UTF_8)));

            log.debug("Publishing telemetry for shipment '{}' to topic '{}' with key '{}' [traceId={}]",
                payload.shipmentId(), rawTopic, partitionKey, traceId);

            return kafkaTemplate.send(record);
        } catch (Exception e) {
            log.error("Failed to serialize or publish telemetry payload for shipment '{}'", payload.shipmentId(), e);
            CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /**
     * Sends a raw payload with the given key directly to the telemetry topic.
     * Used for testing DLQ poison pill resilience and direct gateway integrations.
     */
    public CompletableFuture<SendResult<String, String>> sendRaw(String key, String rawJson) {
        ProducerRecord<String, String> record = new ProducerRecord<>(rawTopic, key, rawJson);
        record.headers().add(new RecordHeader("X-Produced-At", Instant.now().toString().getBytes(StandardCharsets.UTF_8)));
        return kafkaTemplate.send(record);
    }
}
