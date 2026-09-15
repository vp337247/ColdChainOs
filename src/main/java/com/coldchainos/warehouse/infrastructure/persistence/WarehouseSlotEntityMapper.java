package com.coldchainos.warehouse.infrastructure.persistence;

import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.ThermalCategory;
import com.coldchainos.warehouse.domain.*;

import java.math.BigDecimal;
import java.util.List;

public class WarehouseSlotEntityMapper {

    public static WarehouseSlotJpaEntity toEntity(WarehouseSlot domain) {
        WarehouseSlotJpaEntity entity = new WarehouseSlotJpaEntity();
        entity.setId(domain.getId().value());
        entity.setWarehouseId(domain.getWarehouseId().value());
        entity.setSlotCode(domain.getSlotCode());
        entity.setThermalCategory(domain.getThermalCategory().name());
        entity.setMaxCapacityKg(BigDecimal.valueOf(domain.getMaxCapacityKg()));
        entity.setVersion(domain.getVersion());

        for (SlotReservation res : domain.getReservations()) {
            SlotReservationJpaEntity resEntity = new SlotReservationJpaEntity();
            resEntity.setId(res.getId());
            resEntity.setShipmentId(res.getShipmentId().value());
            resEntity.setStartTime(res.getTimeWindow().startTime());
            resEntity.setEndTime(res.getTimeWindow().endTime());
            resEntity.setStatus(res.getStatus().name());
            resEntity.setCreatedAt(res.getCreatedAt());
            entity.addReservation(resEntity);
        }

        return entity;
    }

    public static WarehouseSlot toDomain(WarehouseSlotJpaEntity entity) {
        SlotId slotId = new SlotId(entity.getId());
        WarehouseId warehouseId = WarehouseId.of(entity.getWarehouseId());
        ThermalCategory category = ThermalCategory.valueOf(entity.getThermalCategory());

        List<SlotReservation> reservations = entity.getReservations().stream()
            .map(r -> new SlotReservation(
                r.getId(),
                new ShipmentId(r.getShipmentId()),
                TimeWindow.of(r.getStartTime(), r.getEndTime()),
                SlotReservationStatus.valueOf(r.getStatus()),
                r.getCreatedAt()
            ))
            .toList();

        return WarehouseSlot.reconstitute(
            slotId,
            warehouseId,
            entity.getSlotCode(),
            category,
            entity.getMaxCapacityKg().doubleValue(),
            reservations,
            entity.getVersion()
        );
    }
}
