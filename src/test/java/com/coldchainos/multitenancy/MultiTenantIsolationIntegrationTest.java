package com.coldchainos.multitenancy;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shared.multitenancy.TenantContext;
import com.coldchainos.shipment.domain.*;
import com.coldchainos.tenant.application.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Schema-per-Tenant isolation in PostgreSQL.
 *
 * Guarantees:
 * 1. Queries from Tenant A cannot see records belonging to Tenant B.
 * 2. Unique constraints (such as tracking numbers) are scoped strictly per tenant schema.
 * 3. PostgreSQL physical schema separation is verified via system catalogs.
 */
@SpringBootTest
class MultiTenantIsolationIntegrationTest {

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final TenantId tenantAlpha = TenantId.of("pharma_alpha");
    private final TenantId tenantBeta = TenantId.of("food_beta");

    @BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantAlpha, "Pharma Alpha Importer");
        provisioningService.provisionTenant(tenantBeta, "Food Beta Logistics");
        jdbcTemplate.execute("TRUNCATE TABLE tenant_pharma_alpha.shipments CASCADE;");
        jdbcTemplate.execute("TRUNCATE TABLE tenant_food_beta.shipments CASCADE;");
    }

    @Test
    @DisplayName("Should enforce strict schema isolation: Tenant Beta cannot see Tenant Alpha shipments, and unique constraints are schema-scoped")
    void shouldEnforceStrictSchemaIsolationBetweenTenants() {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        TrackingNumber sharedTrackingNumber = TrackingNumber.of("SHP-2026-" + suffix);
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.REFRIGERATED_2_TO_8, 300);

        Location origin = Location.of("JFK", "JFK Hub", "New York", "US");
        Location destination = Location.of("ORD", "O'Hare Hub", "Chicago", "US");

        // 1. Act as Tenant Alpha: Create shipment
        ShipmentId shipmentAlphaId = TenantContext.executeAs(tenantAlpha, () -> {
            TransitLeg leg = TransitLeg.of(1, origin, destination, Instant.now(), Instant.now().plusSeconds(7200));
            Shipment shipment = Shipment.create(tenantAlpha, sharedTrackingNumber, threshold, List.of(leg));
            shipmentRepository.save(shipment);
            return shipment.getId();
        });

        // 2. Verify Tenant Alpha can read its shipment
        TenantContext.executeAs(tenantAlpha, () -> {
            Optional<Shipment> found = shipmentRepository.findById(shipmentAlphaId);
            assertThat(found).isPresent();
            assertThat(found.get().getTrackingNumber()).isEqualTo(sharedTrackingNumber);
        });

        // 3. Act as Tenant Beta: Attempt to read Tenant Alpha's shipment
        TenantContext.executeAs(tenantBeta, () -> {
            // Must return empty! The table in tenant_food_beta does not contain shipmentAlphaId
            Optional<Shipment> leakAttempt = shipmentRepository.findById(shipmentAlphaId);
            assertThat(leakAttempt)
                .as("Tenant Beta must NOT be able to find Tenant Alpha's shipment by ID")
                .isEmpty();

            Optional<Shipment> byTracking = shipmentRepository.findByTrackingNumber(tenantAlpha, sharedTrackingNumber);
            assertThat(byTracking)
                .as("Tenant Beta search path must NOT find Tenant Alpha's tracking number")
                .isEmpty();

            // 4. Tenant Beta creates a shipment with the EXACT SAME tracking number!
            // In a shared schema with global unique constraint, this would throw a duplicate key error.
            // In schema-per-tenant, each tenant has an independent table, so this MUST succeed!
            TransitLeg betaLeg = TransitLeg.of(1, origin, destination, Instant.now(), Instant.now().plusSeconds(7200));
            Shipment betaShipment = Shipment.create(tenantBeta, sharedTrackingNumber, threshold, List.of(betaLeg));
            shipmentRepository.save(betaShipment);

            Optional<Shipment> betaFound = shipmentRepository.findById(betaShipment.getId());
            assertThat(betaFound).isPresent();
            assertThat(betaFound.get().getId()).isNotEqualTo(shipmentAlphaId);
            assertThat(betaFound.get().getTrackingNumber()).isEqualTo(sharedTrackingNumber);
        });

        // 5. Verify direct database catalog state
        Integer alphaCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tenant_pharma_alpha.shipments", Integer.class
        );
        Integer betaCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tenant_food_beta.shipments", Integer.class
        );

        assertThat(alphaCount).isEqualTo(1);
        assertThat(betaCount).isEqualTo(1);
    }
}
