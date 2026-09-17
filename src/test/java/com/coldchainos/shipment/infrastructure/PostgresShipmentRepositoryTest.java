package com.coldchainos.shipment.infrastructure;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PostgresShipmentRepositoryTest {

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private com.coldchainos.tenant.application.TenantProvisioningService provisioningService;

    private final TenantId tenantId = TenantId.of("pharma_intl");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        provisioningService.provisionTenant(tenantId, "Pharma International Inc");
    }

    @Test
    @DisplayName("Should successfully persist and retrieve a full Shipment aggregate with legs and custody records")
    void shouldPersistAndRetrieveFullShipmentAggregate() {
        com.coldchainos.shared.multitenancy.TenantContext.executeAs(tenantId, () -> {
            String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-" + suffix);
            TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.ULTRA_COLD_MINUS_80, 600);

        Location origin = Location.of("BOS", "Boston Pharma Depot", "Boston", "US");
        Location destination = Location.of("ZRH", "Zurich Cold Bay", "Zurich", "CH");

        TransitLeg leg = TransitLeg.of(1, origin, destination, Instant.now(), Instant.now().plusSeconds(43200));
        leg.assignCarrier(CarrierId.of("SWISS_CARGO"));

        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, List.of(leg));
        shipment.assignCarrier(leg.getId(), CarrierId.of("SWISS_CARGO"));
        shipment.startTransit();

        shipment.recordCustodyHandoff(
            "DEPOT_LEAD_ALICE",
            "CARRIER_DRIVER_BOB",
            BigDecimal.valueOf(-72.5),
            "sha256_cryptographic_signature_verified"
        );

        // Persist to PostgreSQL
        shipmentRepository.save(shipment);

        // Retrieve by ID
        Optional<Shipment> retrievedOpt = shipmentRepository.findById(shipment.getId());
        assertThat(retrievedOpt).isPresent();

        Shipment retrieved = retrievedOpt.get();
        assertThat(retrieved.getId()).isEqualTo(shipment.getId());
        assertThat(retrieved.getTenantId()).isEqualTo(tenantId);
        assertThat(retrieved.getTrackingNumber()).isEqualTo(trackingNumber);
        assertThat(retrieved.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(retrieved.getThreshold().category()).isEqualTo(ThermalCategory.ULTRA_COLD_MINUS_80);
        assertThat(retrieved.getThreshold().minCelsius()).isEqualByComparingTo("-80.00");
        assertThat(retrieved.getThreshold().maxCelsius()).isEqualByComparingTo("-60.00");

        // Verify Legs
        assertThat(retrieved.getLegs()).hasSize(1);
        TransitLeg retrievedLeg = retrieved.getLegs().get(0);
        assertThat(retrievedLeg.getOrigin().code()).isEqualTo("BOS");
        assertThat(retrievedLeg.getDestination().code()).isEqualTo("ZRH");
        assertThat(retrievedLeg.getAssignedCarrierId()).isEqualTo(CarrierId.of("SWISS_CARGO"));
        assertThat(retrievedLeg.getStatus()).isEqualTo(LegStatus.IN_PROGRESS);

        // Verify Custody Records
        assertThat(retrieved.getCustodyHistory()).hasSize(1);
        CustodyRecord retrievedCustody = retrieved.getCustodyHistory().get(0);
        assertThat(retrievedCustody.getReleasingParty()).isEqualTo("DEPOT_LEAD_ALICE");
        assertThat(retrievedCustody.getReceivingParty()).isEqualTo("CARRIER_DRIVER_BOB");
        assertThat(retrievedCustody.getSurfaceTemperatureCelsius()).isEqualByComparingTo("-72.50");

        // Retrieve by Tenant & Tracking Number
        Optional<Shipment> byTrackingOpt = shipmentRepository.findByTrackingNumber(tenantId, trackingNumber);
        assertThat(byTrackingOpt).isPresent();
        assertThat(byTrackingOpt.get().getId()).isEqualTo(shipment.getId());
        });
    }
}
