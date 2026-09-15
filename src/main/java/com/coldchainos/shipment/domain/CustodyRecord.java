package com.coldchainos.shipment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable entity representing a legally binding physical custody transfer.
 */
public class CustodyRecord {

    private final CustodyId id;
    private final String releasingParty;
    private final String receivingParty;
    private final BigDecimal surfaceTemperatureCelsius;
    private final Instant recordedAt;
    private final String digitalSignature;

    public CustodyRecord(
        CustodyId id,
        String releasingParty,
        String receivingParty,
        BigDecimal surfaceTemperatureCelsius,
        Instant recordedAt,
        String digitalSignature
    ) {
        this.id = Objects.requireNonNull(id, "CustodyId cannot be null");
        this.releasingParty = Objects.requireNonNull(releasingParty, "releasingParty cannot be null");
        this.receivingParty = Objects.requireNonNull(receivingParty, "receivingParty cannot be null");
        this.surfaceTemperatureCelsius = Objects.requireNonNull(surfaceTemperatureCelsius, "surfaceTemperatureCelsius cannot be null");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt cannot be null");
        this.digitalSignature = Objects.requireNonNull(digitalSignature, "digitalSignature cannot be null");
    }

    public static CustodyRecord of(
        String releasingParty,
        String receivingParty,
        BigDecimal surfaceTemperatureCelsius,
        String digitalSignature
    ) {
        return new CustodyRecord(
            CustodyId.newId(),
            releasingParty,
            receivingParty,
            surfaceTemperatureCelsius,
            Instant.now(),
            digitalSignature
        );
    }

    public CustodyId getId() {
        return id;
    }

    public String getReleasingParty() {
        return releasingParty;
    }

    public String getReceivingParty() {
        return receivingParty;
    }

    public BigDecimal getSurfaceTemperatureCelsius() {
        return surfaceTemperatureCelsius;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getDigitalSignature() {
        return digitalSignature;
    }
}
