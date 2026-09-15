package com.coldchainos.shipment.infrastructure.persistence;

import com.coldchainos.shared.domain.TenantId;
import com.coldchainos.shipment.domain.*;

import java.util.List;

public class ShipmentEntityMapper {

    public static ShipmentJpaEntity toEntity(Shipment domain) {
        ShipmentJpaEntity entity = new ShipmentJpaEntity();
        entity.setId(domain.getId().value());
        entity.setTenantId(domain.getTenantId().value());
        entity.setTrackingNumber(domain.getTrackingNumber().value());
        entity.setStatus(domain.getStatus().name());
        entity.setPreQuarantineStatus(domain.getPreQuarantineStatus() != null ? domain.getPreQuarantineStatus().name() : null);
        entity.setMinTemperature(domain.getThreshold().minCelsius());
        entity.setMaxTemperature(domain.getThreshold().maxCelsius());
        entity.setMaxExcursionSeconds(domain.getThreshold().maxExcursionSeconds());
        entity.setThermalCategory(domain.getThreshold().category().name());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setDeliveredAt(domain.getDeliveredAt());
        entity.setProofOfDeliverySignature(domain.getProofOfDeliverySignature());

        for (TransitLeg leg : domain.getLegs()) {
            TransitLegJpaEntity legEntity = new TransitLegJpaEntity();
            legEntity.setId(leg.getId().value());
            legEntity.setSequenceNumber(leg.getSequenceNumber());
            legEntity.setOriginCode(leg.getOrigin().code());
            legEntity.setOriginName(leg.getOrigin().name());
            legEntity.setOriginCity(leg.getOrigin().city());
            legEntity.setOriginCountry(leg.getOrigin().countryCode());
            legEntity.setDestinationCode(leg.getDestination().code());
            legEntity.setDestinationName(leg.getDestination().name());
            legEntity.setDestinationCity(leg.getDestination().city());
            legEntity.setDestinationCountry(leg.getDestination().countryCode());
            legEntity.setAssignedCarrierId(leg.getAssignedCarrierId() != null ? leg.getAssignedCarrierId().value() : null);
            legEntity.setStatus(leg.getStatus().name());
            legEntity.setEstimatedDeparture(leg.getEstimatedDeparture());
            legEntity.setEstimatedArrival(leg.getEstimatedArrival());
            entity.addLeg(legEntity);
        }

        for (CustodyRecord custody : domain.getCustodyHistory()) {
            CustodyRecordJpaEntity custodyEntity = new CustodyRecordJpaEntity();
            custodyEntity.setId(custody.getId().value());
            custodyEntity.setReleasingParty(custody.getReleasingParty());
            custodyEntity.setReceivingParty(custody.getReceivingParty());
            custodyEntity.setSurfaceTemperature(custody.getSurfaceTemperatureCelsius());
            custodyEntity.setRecordedAt(custody.getRecordedAt());
            custodyEntity.setDigitalSignature(custody.getDigitalSignature());
            entity.addCustodyRecord(custodyEntity);
        }

        return entity;
    }

    public static Shipment toDomain(ShipmentJpaEntity entity) {
        TenantId tenantId = TenantId.of(entity.getTenantId());
        ShipmentId shipmentId = new ShipmentId(entity.getId());
        TrackingNumber trackingNumber = TrackingNumber.of(entity.getTrackingNumber());
        ThermalCategory category = ThermalCategory.valueOf(entity.getThermalCategory());
        TemperatureThreshold threshold = new TemperatureThreshold(
            entity.getMinTemperature(),
            entity.getMaxTemperature(),
            entity.getMaxExcursionSeconds(),
            category
        );

        List<TransitLeg> legs = entity.getLegs().stream()
            .map(l -> TransitLeg.reconstitute(
                new LegId(l.getId()),
                l.getSequenceNumber(),
                Location.of(l.getOriginCode(), l.getOriginName(), l.getOriginCity(), l.getOriginCountry()),
                Location.of(l.getDestinationCode(), l.getDestinationName(), l.getDestinationCity(), l.getDestinationCountry()),
                l.getAssignedCarrierId() != null ? CarrierId.of(l.getAssignedCarrierId()) : null,
                LegStatus.valueOf(l.getStatus()),
                l.getEstimatedDeparture(),
                l.getEstimatedArrival()
            ))
            .toList();

        List<CustodyRecord> custodyRecords = entity.getCustodyRecords().stream()
            .map(c -> new CustodyRecord(
                new CustodyId(c.getId()),
                c.getReleasingParty(),
                c.getReceivingParty(),
                c.getSurfaceTemperature(),
                c.getRecordedAt(),
                c.getDigitalSignature()
            ))
            .toList();

        ShipmentStatus status = ShipmentStatus.valueOf(entity.getStatus());
        ShipmentStatus preQuarantine = entity.getPreQuarantineStatus() != null ? ShipmentStatus.valueOf(entity.getPreQuarantineStatus()) : null;

        return Shipment.reconstitute(
            shipmentId,
            tenantId,
            trackingNumber,
            threshold,
            status,
            preQuarantine,
            legs,
            custodyRecords,
            entity.getCreatedAt(),
            entity.getDeliveredAt(),
            entity.getProofOfDeliverySignature()
        );
    }
}
