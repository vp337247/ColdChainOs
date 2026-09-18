package com.coldchainos.shared.ratelimit;

/**
 * Result of an atomic Redis token bucket rate limit evaluation.
 *
 * @param allowed             true if request is permitted, false if throttled
 * @param remainingTokens     available tokens in the bucket after this request
 * @param retryAfterSeconds   estimated seconds until sufficient tokens are replenished
 * @param capacity            maximum burst capacity of the bucket
 */
public record RateLimitResult(
    boolean allowed,
    long remainingTokens,
    long retryAfterSeconds,
    long capacity
) {}
