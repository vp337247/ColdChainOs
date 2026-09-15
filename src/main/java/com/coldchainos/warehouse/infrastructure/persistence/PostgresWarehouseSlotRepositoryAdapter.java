package com.coldchainos.warehouse.infrastructure.persistence;

import com.coldchainos.warehouse.domain.SlotId;
import com.coldchainos.warehouse.domain.WarehouseSlot;
import com.coldchainos.warehouse.domain.WarehouseSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PostgresWarehouseSlotRepositoryAdapter implements WarehouseSlotRepository {

    private final SpringDataWarehouseSlotRepository springDataRepository;

    @Override
    @Transactional
    public WarehouseSlot save(WarehouseSlot slot) {
        WarehouseSlotJpaEntity entity = WarehouseSlotEntityMapper.toEntity(slot);
        WarehouseSlotJpaEntity saved = springDataRepository.save(entity);
        return WarehouseSlotEntityMapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WarehouseSlot> findById(SlotId id) {
        return springDataRepository.findByIdWithDetails(id.value())
            .map(WarehouseSlotEntityMapper::toDomain);
    }

    @Override
    @Transactional
    public Optional<WarehouseSlot> findByIdWithPessimisticLock(SlotId id) {
        return springDataRepository.findByIdWithPessimisticLock(id.value())
            .map(entity -> {
                entity.getReservations().size();
                return WarehouseSlotEntityMapper.toDomain(entity);
            });
    }
}
