package com.coldchainos.saga.dispatch;

import com.coldchainos.shared.domain.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service managing certified reefer carrier transport contracts and bookings.
 * Supports forward booking and backward compensation cancellation.
 */
@Slf4j
@Service
public class CarrierBookingService {

    private final Map<String, String> activeBookings = new ConcurrentHashMap<>();
    private final AtomicBoolean simulateCapacityDepleted = new AtomicBoolean(false);

    /**
     * Books transport capacity with the contracted carrier.
     */
    public String bookCarrier(TenantId tenantId, UUID shipmentId, String carrierId) {
        if (simulateCapacityDepleted.get()) {
            log.warn("[CarrierBooking] Booking failed: No certified reefer capacity for carrier '{}'", carrierId);
            throw new IllegalStateException("Carrier " + carrierId + " has no certified reefer capacity available");
        }

        String bookingId = "BKG-" + carrierId.toUpperCase() + "-" + shipmentId.toString().substring(0, 8);
        activeBookings.put(bookingId, carrierId);
        log.info("[CarrierBooking] Successfully booked capacity with carrier '{}', ref='{}'", carrierId, bookingId);
        return bookingId;
    }

    /**
     * Compensating action: cancels and releases reserved carrier transport.
     */
    public boolean cancelCarrierBooking(TenantId tenantId, UUID shipmentId, String bookingId) {
        if (bookingId == null) {
            return false;
        }
        String removed = activeBookings.remove(bookingId);
        if (removed != null) {
            log.info("[CarrierCompensation] Successfully cancelled carrier booking '{}' for shipment '{}'", bookingId, shipmentId);
            return true;
        }
        log.warn("[CarrierCompensation] Booking reference '{}' not found or already cancelled", bookingId);
        return false;
    }

    public boolean isBookingActive(String bookingId) {
        return activeBookings.containsKey(bookingId);
    }

    public void setSimulateCapacityDepleted(boolean depleted) {
        this.simulateCapacityDepleted.set(depleted);
    }

    public void reset() {
        this.activeBookings.clear();
        this.simulateCapacityDepleted.set(false);
    }
}
