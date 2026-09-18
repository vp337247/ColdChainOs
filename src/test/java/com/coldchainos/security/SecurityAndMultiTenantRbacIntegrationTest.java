package com.coldchainos.security;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shared.security.JwtTokenProvider;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryViewJpaEntity;
import com.coldchainos.shipment.infrastructure.projection.ShipmentSummaryViewJpaRepository;
import com.coldchainos.tenant.application.TenantProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 10 Integration Test: Security, Authentication & Multi-Tenant Authorization (RBAC).
 *
 * Verifies:
 * 1. Token generation via public /api/v1/auth/token endpoint.
 * 2. Rejection of unauthenticated, malformed, or expired tokens (401 Unauthorized).
 * 3. Cross-tenant spoofing prevention across path parameters and headers (403 Forbidden).
 * 4. Fine-grained RBAC enforcement (AUDITOR read-only vs LOGISTICS_OPERATOR mutation).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityAndMultiTenantRbacIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private ShipmentSummaryViewJpaRepository summaryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final TenantId tenantAlpha = TenantId.of("sec_tenant_alpha");
    private final TenantId tenantBeta = TenantId.of("sec_tenant_beta");
    private UUID testShipmentId;

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantAlpha, "Security Test Alpha Corp");
        provisioningService.provisionTenant(tenantBeta, "Security Test Beta Ltd");

        testShipmentId = UUID.randomUUID();

        // Seed a sample summary record in tenantAlpha schema
        TenantContext.executeAs(tenantAlpha, () -> {
            summaryRepository.deleteAll();
            ShipmentSummaryViewJpaEntity entity = new ShipmentSummaryViewJpaEntity(
                testShipmentId,
                tenantAlpha.value(),
                "SHP-2026-SEC01",
                "CREATED",
                "REFRIGERATED_2_TO_8",
                "FRA",
                "Frankfurt",
                "CDG",
                "Paris",
                null,
                BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(8.0),
                1,
                false,
                Instant.now(),
                null,
                "ShipmentCreatedEvent",
                Instant.now(),
                Instant.now()
            );
            summaryRepository.saveAndFlush(entity);
        });
    }

    @Test
    @DisplayName("Should issue valid Bearer JWT from public auth endpoint")
    void shouldIssueValidJwtToken() throws Exception {
        Map<String, Object> req = Map.of(
            "userId", "operator-john",
            "tenantId", tenantAlpha.value(),
            "roles", Set.of("LOGISTICS_OPERATOR")
        );

        mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.tenantId").value(tenantAlpha.value()));
    }

    @Test
    @DisplayName("Should reject unauthenticated access with 401 Unauthorized")
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/shipments/" + tenantAlpha.value() + "/summary/" + testShipmentId))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should reject malformed Bearer token with 401 Unauthorized")
    void shouldRejectMalformedToken() throws Exception {
        mockMvc.perform(get("/api/v1/shipments/" + tenantAlpha.value() + "/summary/" + testShipmentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token.here"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should prevent cross-tenant access when Tenant Alpha user attempts to query Tenant Beta data")
    void shouldPreventCrossTenantParameterTampering() throws Exception {
        // User is authenticated for tenantAlpha
        String alphaToken = tokenProvider.generateToken(
            "user-alpha-01",
            tenantAlpha.value(),
            Set.of("LOGISTICS_OPERATOR"),
            Duration.ofHours(1)
        );

        // Caller attempts to access tenantBeta's URL
        mockMvc.perform(get("/api/v1/shipments/" + tenantBeta.value() + "/summary/" + testShipmentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alphaToken))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should prevent cross-tenant header spoofing when X-Tenant-ID does not match token claim")
    void shouldPreventCrossTenantHeaderSpoofing() throws Exception {
        String alphaToken = tokenProvider.generateToken(
            "user-alpha-01",
            tenantAlpha.value(),
            Set.of("LOGISTICS_OPERATOR"),
            Duration.ofHours(1)
        );

        // Header claims to be tenantBeta while token belongs to tenantAlpha
        mockMvc.perform(get("/api/v1/shipments/" + tenantAlpha.value() + "/summary/" + testShipmentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + alphaToken)
                .header("X-Tenant-ID", tenantBeta.value()))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should enforce RBAC: AUDITOR can read summary view but is forbidden from mutating shipment state")
    void shouldEnforceRbacPermissions() throws Exception {
        String auditorToken = tokenProvider.generateToken(
            "auditor-jane",
            tenantAlpha.value(),
            Set.of("AUDITOR"),
            Duration.ofHours(1)
        );

        // 1. Auditor CAN read summary view
        mockMvc.perform(get("/api/v1/shipments/" + tenantAlpha.value() + "/summary/" + testShipmentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trackingNumber").value("SHP-2026-SEC01"));

        // 2. Auditor CANNOT dispatch or mutate shipment state -> 403 Forbidden
        mockMvc.perform(post("/api/v1/shipments/" + tenantAlpha.value() + "/dispatch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrierId\": \"DHL\"}"))
            .andExpect(status().isForbidden());

        // 3. LOGISTICS_OPERATOR CAN dispatch shipment -> 200 OK
        String operatorToken = tokenProvider.generateToken(
            "operator-bob",
            tenantAlpha.value(),
            Set.of("LOGISTICS_OPERATOR"),
            Duration.ofHours(1)
        );

        mockMvc.perform(post("/api/v1/shipments/" + tenantAlpha.value() + "/dispatch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrierId\": \"DHL\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISPATCHED"));
    }
}
