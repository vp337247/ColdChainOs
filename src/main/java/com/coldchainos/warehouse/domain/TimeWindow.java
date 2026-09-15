package com.coldchainos.warehouse.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Value Object representing a bounded time interval for warehouse slot bookings.
 */
public record TimeWindow(Instant startTime, Instant endTime) {

    public TimeWindow {
        Objects.requireNonNull(startTime, "startTime cannot be null");
        Objects.requireNonNull(endTime, "endTime cannot be null");
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("endTime must be strictly after startTime");
        }
    }

    public static TimeWindow of(Instant start, Instant end) {
        return new TimeWindow(start, end);
    }

    /**
     * Checks if this time window overlaps with another time window.
     * Two intervals [s1, e1) and [s2, e2) overlap if: s1 < e2 AND s2 < e1
     */
    public boolean overlapsWith(TimeWindow other) {
        Objects.requireNonNull(other, "other TimeWindow cannot be null");
        return this.startTime.isBefore(other.endTime) && other.startTime.isBefore(this.endTime);
    }
}
