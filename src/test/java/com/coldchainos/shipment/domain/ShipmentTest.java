package com.coldchainos.shipment.domain;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.events.*;
import com.coldchainos.shipment.domain.exceptions.InvalidStateTransitionException;
import com.coldchainos.shipment.domain.exceptions.ShipmentQuarantinedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ShipmentTest {

    private final TenantId tenantId = TenantId.of("pharma_corp");
    private final TrackingNumber trackingNumber = TrackingNumber.of("SHP-2026-PHARMA01");
    private final TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.REFRIGERATED_2_TO_8, 300);

    private final Location origin = Location.of("JFK", "JFK Airport Hub", "New York", "US");
    private final Location intermediate = Location.of("LHR", "Heathrow Cold Bay", "London", "GB");
    private final Location destination = Location.of("FRA", "Frankfurt Pharma Logistics", "Frankfurt", "DE");

    private List<TransitLeg> createStandardTwoLegs() {
        TransitLeg leg1 = TransitLeg.of(1, origin, intermediate, Instant.now(), Instant.now().plusSeconds(36000));
        TransitLeg leg2 = TransitLeg.of(2, intermediate, destination, Instant.now().plusSeconds(40000), Instant.now().plusSeconds(80000));
        return List.of(leg1, leg2);
    }

    @Test
    @DisplayName("Should successfully progress through the full happy-path shipment lifecycle")
    void shouldExecuteFullHappyPathLifecycle() {
        List<TransitLeg> legs = createStandardTwoLegs();

        // 1. Create Shipment
        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, legs);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(shipment.getDomainEvents()).hasSize(1);
        assertThat(shipment.getDomainEvents().get(0)).isInstanceOf(ShipmentCreatedEvent.class);

        // 2. Assign Carrier to Leg 1
        CarrierId carrier1 = CarrierId.of("CARRIER_ALPHA");
        shipment.assignCarrier(legs.get(0).getId(), carrier1);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CREATED); // Still CREATED because leg 2 is unassigned

        // 3. Assign Carrier to Leg 2 -> Auto-advances to ASSIGNED
        CarrierId carrier2 = CarrierId.of("CARRIER_BETA");
        shipment.assignCarrier(legs.get(1).getId(), carrier2);
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.ASSIGNED);

        // 4. Start Transit
        shipment.startTransit();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(legs.get(0).getStatus()).isEqualTo(LegStatus.IN_PROGRESS);

        // 5. Record Custody Handoff at London Hub
        shipment.recordCustodyHandoff(
            "CARRIER_ALPHA_DRIVER",
            "LHR_COLD_OPERATOR",
            BigDecimal.valueOf(4.5),
            "sig_sha256_dual_ack_987654"
        );
        assertThat(shipment.getCustodyHistory()).hasSize(1);
        assertThat(shipment.getCustodyHistory().get(0).getSurfaceTemperatureCelsius()).isEqualByComparingTo("4.5");

        // 6. Arrive at Port / Warehouse
        shipment.arriveAtPort();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.AT_PORT);

        shipment.arriveAtWarehouse();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.WAREHOUSE);

        // 7. Deliver Shipment
        shipment.markDelivered("SIGNED_BY_HOSPITAL_PHARMACIST_DOE");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(shipment.getDeliveredAt()).isNotNull();
        assertThat(shipment.getProofOfDeliverySignature()).isEqualTo("SIGNED_BY_HOSPITAL_PHARMACIST_DOE");
    }

    @Test
    @DisplayName("Should prevent illegal state transition jumping from CREATED directly to DELIVERED")
    void shouldRejectIllegalTransitionCreatedToDelivered() {
        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, createStandardTwoLegs());

        assertThatThrownBy(() -> shipment.markDelivered("SOME_SIGNATURE"))
            .isInstanceOf(InvalidStateTransitionException.class)
            .hasMessageContaining("Cannot transition shipment from CREATED to DELIVERED");
    }

    @Test
    @DisplayName("Should prevent cancellation after transit has started")
    void shouldRejectCancellationWhenInTransit() {
        List<TransitLeg> legs = createStandardTwoLegs();
        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, legs);
        shipment.assignCarrier(legs.get(0).getId(), CarrierId.of("C1"));
        shipment.assignCarrier(legs.get(1).getId(), CarrierId.of("C2"));
        shipment.startTransit();

        assertThatThrownBy(() -> shipment.cancel("Customer change of mind"))
            .isInstanceOf(InvalidStateTransitionException.class)
            .hasMessageContaining("Shipment can only be cancelled prior to departure");
    }

    @Test
    @DisplayName("Should enforce quarantine lock on excursion and restore state upon 21 CFR Part 11 sign-off")
    void shouldEnforceQuarantineLockAndAuthorizedRelease() {
        List<TransitLeg> legs = createStandardTwoLegs();
        Shipment shipment = Shipment.create(tenantId, trackingNumber, threshold, legs);
        shipment.assignCarrier(legs.get(0).getId(), CarrierId.of("C1"));
        shipment.assignCarrier(legs.get(1).getId(), CarrierId.of("C2"));
        shipment.startTransit();

        // Temperature excursion detected! Apply quarantine
        UUID incidentId = UUID.randomUUID();
        shipment.applyQuarantine(incidentId, "Temperature breached 12.5C for 22 minutes");

        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.QUARANTINED);
        assertThat(shipment.getPreQuarantineStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);

        // Attempting any movement or delivery while quarantined must fail
        assertThatThrownBy(() -> shipment.arriveAtPort())
            .isInstanceOf(ShipmentQuarantinedException.class)
            .hasMessageContaining("is currently QUARANTINED");

        assertThatThrownBy(() -> shipment.markDelivered("FRAUDULENT_SIGNATURE"))
            .isInstanceOf(ShipmentQuarantinedException.class);

        // QA Officer investigates and formally releases quarantine with electronic signature
        shipment.releaseQuarantine(
            "QA_OFFICER_SARAH_CONNOR",
            "MKT stability calculation shows product remains 99.8% potent within safe kinetic budget.",
            "DIGITAL_SIG_HASH_21CFR11_VERIFIED_7788"
        );

        // Shipment status is cleanly restored to previous operational status (IN_TRANSIT)
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(shipment.getPreQuarantineStatus()).isNull();

        // Movement can now safely resume
        shipment.arriveAtWarehouse();
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.WAREHOUSE);
    }

    @Test
    @DisplayName("Should reject non-contiguous or zero transit legs")
    void shouldRejectInvalidLegsConfiguration() {
        // Empty legs
        assertThatThrownBy(() ->
            Shipment.create(tenantId, trackingNumber, threshold, List.of())
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must have at least one transit leg");

        // Non-contiguous sequence numbers (e.g. sequence 1 and 3, missing 2)
        TransitLeg leg1 = TransitLeg.of(1, origin, intermediate, Instant.now(), Instant.now());
        TransitLeg leg3 = TransitLeg.of(3, intermediate, destination, Instant.now(), Instant.now());

        assertThatThrownBy(() ->
            Shipment.create(tenantId, trackingNumber, threshold, List.of(leg1, leg3))
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contiguous sequence numbers");
    }
}
