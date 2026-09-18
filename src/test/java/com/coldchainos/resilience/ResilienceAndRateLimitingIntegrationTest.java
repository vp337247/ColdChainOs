package com.coldchainos.resilience;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.ratelimit.RateLimitProperties;
import com.coldchainos.shared.ratelimit.RateLimitResult;
import com.coldchainos.shared.ratelimit.TenantRateLimiter;
import com.coldchainos.shared.ratelimit.web.TenantRateLimitingFilter;
import com.coldchainos.shared.security.JwtTokenProvider;
import com.coldchainos.shipment.infrastructure.external.ComplianceAssessment;
import com.coldchainos.shipment.infrastructure.external.ComplianceStatus;
import com.coldchainos.shipment.infrastructure.external.ExternalComplianceClearinghouseClient;
import com.coldchainos.tenant.application.TenantProvisioningService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 Integration Test: Resilience, Circuit Breakers & Distributed Rate Limiting.
 *
 * Verifies:
 * 1. Resilience4j @Retry successfully recovers from transient network glitches.
 * 2. Resilience4j @CircuitBreaker trips to OPEN under sustained outages and executes fallback.
 * 3. Circuit breaker recovers from OPEN -> HALF_OPEN -> CLOSED when service returns to healthy state.
 * 4. Redis Token Bucket rate limits burst traffic and throttles excess requests with HTTP 429 and Retry-After.
 * 5. Multi-tenant quota isolation: throttled Tenant Alpha does not affect Tenant Beta capacity.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ResilienceAndRateLimitingIntegrationTest {

    @Autowired
    private ExternalComplianceClearinghouseClient clearinghouseClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TenantRateLimiter rateLimiter;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private TenantProvisioningService provisioningService;

    private final TenantId tenantAlpha = TenantId.of("resilience_alpha");
    private final TenantId tenantBeta = TenantId.of("resilience_beta");

    @BeforeEach
    void setUp() {
        clearinghouseClient.reset();
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("complianceClearinghouse");
        cb.reset();

        rateLimiter.reset(tenantAlpha);
        rateLimiter.reset(tenantBeta);

        provisioningService.provisionTenant(tenantAlpha, "Resilience Alpha Corp");
        provisioningService.provisionTenant(tenantBeta, "Resilience Beta Corp");
    }

    @AfterEach
    void tearDown() {
        rateLimitProperties.setDefaultCapacity(10);
        rateLimitProperties.setDefaultRefillRatePerSecond(5);
    }

    @Test
    @DisplayName("1. Transient failure should be retried and succeed on second attempt without tripping circuit")
    void shouldRetryTransientFailureAndSucceed() {
        // Configure 2 transient failures (maxAttempts = 3)
        clearinghouseClient.setTransientFailures(2);

        String shipmentId = UUID.randomUUID().toString();
        ComplianceAssessment assessment = clearinghouseClient.verifyRegulatoryClearance(tenantAlpha.value(), shipmentId);

        // Assert: Succeeded after 3 invocations (1 initial + 2 retries)
        assertThat(assessment.isFallback()).isFalse();
        assertThat(assessment.status()).isEqualTo(ComplianceStatus.APPROVED);
        assertThat(clearinghouseClient.getInvocationCount()).isEqualTo(3);

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("complianceClearinghouse");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("2. Sustained failure should trip Circuit Breaker to OPEN and invoke graceful fallback")
    void shouldTripCircuitBreakerToOpenAndExecuteFallback() {
        clearinghouseClient.setForceFailure(true);

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("complianceClearinghouse");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Minimum calls = 4, sliding window = 6, failure threshold = 50%
        // Trigger 4 calls (each retries 3 times and fails)
        for (int i = 0; i < 4; i++) {
            ComplianceAssessment assessment = clearinghouseClient.verifyRegulatoryClearance(
                tenantAlpha.value(),
                "SHIP-" + i
            );
            assertThat(assessment.isFallback()).isTrue();
            assertThat(assessment.status()).isEqualTo(ComplianceStatus.PROVISIONAL_FALLBACK);
        }

        // Circuit Breaker should now be OPEN
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Subsequent call should be short-circuited directly by the circuit breaker without hitting the client method
        int countBeforeShortCircuit = clearinghouseClient.getInvocationCount();
        ComplianceAssessment shortCircuited = clearinghouseClient.verifyRegulatoryClearance(
            tenantAlpha.value(),
            "SHIP-SHORT-CIRCUIT"
        );
        assertThat(shortCircuited.isFallback()).isTrue();
        assertThat(clearinghouseClient.getInvocationCount()).isEqualTo(countBeforeShortCircuit);
    }

    @Test
    @DisplayName("3. Circuit Breaker should recover from OPEN to HALF_OPEN and then back to CLOSED upon recovery")
    void shouldRecoverFromOpenToClosed() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("complianceClearinghouse");

        // Manually transition to OPEN to simulate active breaker
        cb.transitionToOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Transition to HALF_OPEN (permittedNumberOfCallsInHalfOpenState = 2)
        cb.transitionToHalfOpenState();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        clearinghouseClient.setForceFailure(false);

        // Execute 2 successful calls in HALF_OPEN state
        ComplianceAssessment call1 = clearinghouseClient.verifyRegulatoryClearance(tenantAlpha.value(), "SHIP-REC-1");
        ComplianceAssessment call2 = clearinghouseClient.verifyRegulatoryClearance(tenantAlpha.value(), "SHIP-REC-2");

        assertThat(call1.isFallback()).isFalse();
        assertThat(call2.isFallback()).isFalse();

        // Circuit Breaker must automatically recover back to CLOSED
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("4. Redis Token Bucket should allow burst up to capacity and throttle excess requests")
    void shouldEnforceDistributedTokenBucketRateLimiting() {
        long capacity = 5;
        long refillRate = 1;

        // Exhaust all 5 tokens
        for (int i = 0; i < capacity; i++) {
            RateLimitResult result = rateLimiter.tryAcquire(tenantAlpha, capacity, refillRate, 1);
            assertThat(result.allowed()).isTrue();
            assertThat(result.remainingTokens()).isEqualTo(capacity - 1 - i);
        }

        // 6th call should be throttled
        RateLimitResult throttled = rateLimiter.tryAcquire(tenantAlpha, capacity, refillRate, 1);
        assertThat(throttled.allowed()).isFalse();
        assertThat(throttled.remainingTokens()).isEqualTo(0);
        assertThat(throttled.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("5. Multi-Tenant Rate Limiting Isolation: Throttling Tenant Alpha must not affect Tenant Beta")
    void shouldIsolateRateLimitsAcrossTenants() {
        long capacity = 3;
        long refillRate = 1;

        // Exhaust Tenant Alpha tokens
        for (int i = 0; i < capacity; i++) {
            rateLimiter.tryAcquire(tenantAlpha, capacity, refillRate, 1);
        }
        RateLimitResult alphaThrottled = rateLimiter.tryAcquire(tenantAlpha, capacity, refillRate, 1);
        assertThat(alphaThrottled.allowed()).isFalse();

        // Tenant Beta must have full independent capacity
        RateLimitResult betaResult = rateLimiter.tryAcquire(tenantBeta, capacity, refillRate, 1);
        assertThat(betaResult.allowed()).isTrue();
        assertThat(betaResult.remainingTokens()).isEqualTo(capacity - 1);
    }

    @Test
    @DisplayName("6. HTTP TenantRateLimitingFilter should return 429 Too Many Requests with RFC headers when quota exceeded")
    void shouldReturn429WithHeadersWhenHttpRateLimitExceeded() throws Exception {
        rateLimitProperties.setDefaultCapacity(5);
        rateLimitProperties.setDefaultRefillRatePerSecond(0);

        String tokenAlpha = jwtTokenProvider.generateToken("user-alpha", tenantAlpha.value(), Set.of("LOGISTICS_OPERATOR"));
        java.util.Map<String, String> payload = java.util.Map.of("reason", "Standard dispatch");

        // Exhaust tenantAlpha's capacity (5 tokens)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/shipments/" + tenantAlpha.value() + "/dispatch")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload))
                    .header("Authorization", "Bearer " + tokenAlpha))
                .andExpect(status().isOk())
                .andExpect(header().exists(TenantRateLimitingFilter.HEADER_RATE_LIMIT))
                .andExpect(header().exists(TenantRateLimitingFilter.HEADER_RATE_REMAINING));
        }

        // 6th request must be rejected with HTTP 429 Too Many Requests
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/shipments/" + tenantAlpha.value() + "/dispatch")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload))
                .header("Authorization", "Bearer " + tokenAlpha))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(TenantRateLimitingFilter.HEADER_RATE_REMAINING, "0"))
            .andExpect(header().exists(TenantRateLimitingFilter.HEADER_RETRY_AFTER));
    }
}
