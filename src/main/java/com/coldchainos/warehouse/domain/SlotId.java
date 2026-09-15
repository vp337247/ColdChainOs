package com.coldchainos.warehouse.domain;

import java.util.Objects;
import java.util.UUID;

public record SlotId(UUID value) {
    public SlotId {
        Objects.requireNonNull(value, "SlotId cannot be null");
    }

    public static SlotId newId() {
        return new SlotId(UUID.randomUUID());
    }

    public static SlotId of(String uuidString) {
        return new SlotId(UUID.fromString(uuidString));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
