package com.coldchainos.shipment.domain;

/**
 * Standard industry thermal classifications for cold chain payloads.
 */
public enum ThermalCategory {
    ULTRA_COLD_MINUS_80("Ultra Cold (-80°C to -60°C)", -80.0, -60.0),
    FROZEN_MINUS_20("Frozen (-25°C to -15°C)", -25.0, -15.0),
    REFRIGERATED_2_TO_8("Refrigerated (+2°C to +8°C)", 2.0, 8.0),
    CONTROLLED_ROOM_TEMP_15_TO_25("Controlled Room Temperature (+15°C to +25°C)", 15.0, 25.0);

    private final String label;
    private final double defaultMinCelsius;
    private final double defaultMaxCelsius;

    ThermalCategory(String label, double defaultMinCelsius, double defaultMaxCelsius) {
        this.label = label;
        this.defaultMinCelsius = defaultMinCelsius;
        this.defaultMaxCelsius = defaultMaxCelsius;
    }

    public String getLabel() {
        return label;
    }

    public double getDefaultMinCelsius() {
        return defaultMinCelsius;
    }

    public double getDefaultMaxCelsius() {
        return defaultMaxCelsius;
    }
}
