package com.coldchainos.warehouse.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "warehouse_slots")
@Getter
@Setter
@NoArgsConstructor
public class WarehouseSlotJpaEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false, length = 64)
    private String warehouseId;

    @Column(name = "slot_code", nullable = false, length = 32)
    private String slotCode;

    @Column(name = "thermal_category", nullable = false, length = 32)
    private String thermalCategory;

    @Column(name = "max_capacity_kg", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxCapacityKg;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "slot", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("startTime ASC")
    private List<SlotReservationJpaEntity> reservations = new ArrayList<>();

    public void addReservation(SlotReservationJpaEntity res) {
        reservations.add(res);
        res.setSlot(this);
    }
}
