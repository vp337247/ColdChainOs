package com.coldchainos.shared.ratelimit;

import com.coldchainos.shared.domain.TenantId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Distributed Token Bucket Rate Limiter backed by Redis and executed via an atomic Lua script.
 * Guarantees per-tenant isolation without race conditions or boundary double-bursts.
 */
@Service
public class TenantRateLimiter {

    private static final String TOKEN_BUCKET_LUA = """
        local key = KEYS[1]
        local capacity = tonumber(ARGV[1])
        local refill_rate = tonumber(ARGV[2])
        local requested = tonumber(ARGV[3])
        local now = tonumber(ARGV[4])

        local data = redis.call('HMGET', key, 'tokens', 'last_updated')
        local tokens = tonumber(data[1])
        local last_updated = tonumber(data[2])

        if tokens == nil then
            tokens = capacity
            last_updated = now
        else
            local elapsed = math.max(0, now - last_updated)
            local generated = elapsed * refill_rate
            tokens = math.min(capacity, tokens + generated)
            if generated > 0 then
                last_updated = now
            end
        end

        if tokens >= requested then
            tokens = tokens - requested
            redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)
            redis.call('EXPIRE', key, 3600)
            return {1, tokens, 0, capacity}
        else
            local needed = requested - tokens
            local retry_after = math.ceil(needed / refill_rate)
            redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)
            redis.call('EXPIRE', key, 3600)
            return {0, tokens, retry_after, capacity}
        end
        """;

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final RedisScript<List> redisScript;

    public TenantRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.redisScript = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, List.class);
    }

    /**
     * Attempts to acquire 1 token using default tenant capacity and refill rate.
     */
    public RateLimitResult tryAcquire(TenantId tenantId) {
        return tryAcquire(tenantId, properties.getDefaultCapacity(), properties.getDefaultRefillRatePerSecond(), 1);
    }

    /**
     * Attempts to acquire specified tokens with custom bucket capacity and refill rate.
     */
    @SuppressWarnings("unchecked")
    public RateLimitResult tryAcquire(TenantId tenantId, long capacity, long refillRatePerSecond, long requestedTokens) {
        String key = "coldchain:ratelimit:" + tenantId.value();
        long nowSeconds = Instant.now().getEpochSecond();

        List<Object> rawResult = redisTemplate.execute(
            redisScript,
            Collections.singletonList(key),
            String.valueOf(capacity),
            String.valueOf(refillRatePerSecond),
            String.valueOf(requestedTokens),
            String.valueOf(nowSeconds)
        );

        if (rawResult == null || rawResult.size() < 4) {
            // Fail-open defensively in case of unexpected script failure
            return new RateLimitResult(true, capacity, 0, capacity);
        }

        long allowedCode = toLong(rawResult.get(0));
        long remainingTokens = toLong(rawResult.get(1));
        long retryAfter = toLong(rawResult.get(2));
        long maxCapacity = toLong(rawResult.get(3));

        return new RateLimitResult(allowedCode == 1L, remainingTokens, retryAfter, maxCapacity);
    }

    /**
     * Resets rate limit bucket for testing and maintenance purposes.
     */
    public void reset(TenantId tenantId) {
        String key = "coldchain:ratelimit:" + tenantId.value();
        redisTemplate.delete(key);
    }

    private long toLong(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(val));
    }
}
