package com.coldchainos.warehouse.concurrency;

import com.coldchainos.shipment.domain.ShipmentId;
import com.coldchainos.shipment.domain.ThermalCategory;
import com.coldchainos.warehouse.application.WarehouseSlotReservationService;
import com.coldchainos.warehouse.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Concurrency Lab: Warehouse Slot Reservation.
 *
 * Simulates high-contention concurrent traffic racing to reserve the same cold storage slot.
 * Enforces the business invariant: One slot CANNOT be double-booked for overlapping time ranges.
 */
@SpringBootTest
class WarehouseSlotConcurrencyTest {

    @Autowired
    private WarehouseSlotRepository slotRepository;

    @Autowired
    private WarehouseSlotReservationService reservationService;

    @Test
    @DisplayName("Concurrency Lab: 20 simultaneous threads racing for the same slot must yield exactly 1 winner and 19 deterministic conflicts")
    void shouldPreventDoubleBookingUnderHighConcurrentContention() throws InterruptedException {
        // 1. Arrange: Create a physical slot in warehouse
        WarehouseSlot slot = WarehouseSlot.create(
            WarehouseId.of("WH-FRANKFURT-01"),
            "SLOT-ULTRA-B2-" + System.currentTimeMillis(),
            ThermalCategory.ULTRA_COLD_MINUS_80,
            1500.0
        );
        WarehouseSlot savedSlot = slotRepository.save(slot);
        SlotId slotId = savedSlot.getId();

        // Target reservation window: 2-hour storage block
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant end = start.plus(2, ChronoUnit.HOURS);
        TimeWindow window = TimeWindow.of(start, end);

        // 2. Act: Prepare 20 concurrent threads racing simultaneously
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // All 20 threads release simultaneously here!

                    ShipmentId concurrentShipment = ShipmentId.newId();
                    reservationService.reserveWithPessimisticLock(slotId, concurrentShipment, window);
                    successCount.incrementAndGet();
                } catch (SlotReservationException e) {
                    // Expected domain conflict invariant
                    conflictCount.incrementAndGet();
                } catch (Throwable t) {
                    exceptions.add(t);
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // FIRE ALL 20 THREADS AT ONCE

        executor.shutdown();
        boolean finished = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        // 3. Assert: Concurrency correctness
        System.out.println("Concurrency Lab Results:");
        System.out.println("  Successful reservations: " + successCount.get());
        System.out.println("  Conflict rejections:     " + conflictCount.get());
        System.out.println("  Unexpected exceptions:   " + exceptions.size());

        assertThat(exceptions).isEmpty();
        assertThat(successCount.get())
            .as("Exactly one thread must win the reservation")
            .isEqualTo(1);
        assertThat(conflictCount.get())
            .as("Exactly 19 threads must be rejected due to slot conflict")
            .isEqualTo(19);

        // 4. Verify database state
        WarehouseSlot finalSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(finalSlot.getReservations()).hasSize(1);
        assertThat(finalSlot.getReservations().get(0).getTimeWindow()).isEqualTo(window);
        assertThat(finalSlot.getReservations().get(0).isActive()).isTrue();
    }
}
