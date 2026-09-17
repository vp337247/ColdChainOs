package com.coldchainos.shipment.infrastructure.cache;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.SensorId;
import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.TelemetryReading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * In-memory distributed cache repository for hot shipment telemetry state and IoT sensor deduplication.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TelemetryCacheRepository {

    private final RedisTemplate<String, Object> redisTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public static final Duration DEFAULT_SNAPSHOT_TTL = Duration.ofHours(2);
    public static final Duration DEFAULT_DEDUP_TTL = Duration.ofMinutes(10);

    private String buildSnapshotKey(TenantId tenantId, ShipmentId shipmentId) {
        return "coldchain:" + tenantId.value() + ":shipment:" + shipmentId.value() + ":latest_telemetry";
    }

    private String buildDedupKey(SensorId sensorId, Instant timestamp) {
        return "coldchain:dedup:" + sensorId.value() + ":" + timestamp.toEpochMilli();
    }

    /**
     * Atomically locks an incoming IoT sensor packet timestamp to prevent duplicate processing.
     *
     * @return true if packet is unique and lock acquired; false if duplicate packet detected
     */
    public boolean acquireDeduplicationLock(SensorId sensorId, Instant timestamp, Duration ttl) {
        String key = buildDedupKey(sensorId, timestamp);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl != null ? ttl : DEFAULT_DEDUP_TTL);
        boolean isUnique = Boolean.TRUE.equals(acquired);
        if (!isUnique) {
            log.warn("Duplicate IoT sensor reading detected for sensor '{}' at timestamp '{}'. Discarding packet.", sensorId, timestamp);
        }
        return isUnique;
    }

    /**
     * Caches the latest telemetry snapshot for real-time dashboard and tracking queries.
     */
    public void cacheLatestReading(TenantId tenantId, ShipmentId shipmentId, TelemetryReading reading, Duration ttl) {
        String key = buildSnapshotKey(tenantId, shipmentId);
        try {
            String json = objectMapper.writeValueAsString(reading);
            redisTemplate.opsForValue().set(key, json, ttl != null ? ttl : DEFAULT_SNAPSHOT_TTL);
            log.debug("Cached latest telemetry snapshot for shipment '{}' in tenant '{}' (TTL: {})", shipmentId, tenantId, ttl);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to serialize telemetry reading: {}", reading, e);
            throw new IllegalStateException("Failed to serialize telemetry reading", e);
        }
    }

    /**
     * Retrieves the hot telemetry snapshot from Redis.
     */
    public Optional<TelemetryReading> getLatestReading(TenantId tenantId, ShipmentId shipmentId) {
        String key = buildSnapshotKey(tenantId, shipmentId);
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached instanceof String json) {
            try {
                return Optional.of(objectMapper.readValue(json, TelemetryReading.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("Failed to deserialize cached telemetry reading: {}", json, e);
            }
        }
        return Optional.empty();
    }

    /**
     * Explicitly evicts the telemetry snapshot (e.g., upon shipment cancellation or delivery).
     */
    public void evictLatestReading(TenantId tenantId, ShipmentId shipmentId) {
        String key = buildSnapshotKey(tenantId, shipmentId);
        redisTemplate.delete(key);
        log.debug("Evicted latest telemetry snapshot for shipment '{}'", shipmentId);
    }
}
