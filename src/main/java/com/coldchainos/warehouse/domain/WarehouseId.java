package com.coldchainos.warehouse.domain;

import java.util.Objects;

public record WarehouseId(String value) {
    public WarehouseId {
        Objects.requireNonNull(value, "WarehouseId cannot be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("WarehouseId cannot be blank");
        }
        value = trimmed;
    }

    public static WarehouseId of(String value) {
        return new WarehouseId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
