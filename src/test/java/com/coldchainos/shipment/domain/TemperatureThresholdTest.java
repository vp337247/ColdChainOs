package com.coldchainos.shipment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class TemperatureThresholdTest {

    @Test
    @DisplayName("Should create valid temperature threshold for Refrigerated category (+2°C to +8°C)")
    void shouldCreateValidRefrigeratedThreshold() {
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.REFRIGERATED_2_TO_8, 300);

        assertThat(threshold.minCelsius()).isEqualByComparingTo("2.00");
        assertThat(threshold.maxCelsius()).isEqualByComparingTo("8.00");
        assertThat(threshold.maxExcursionSeconds()).isEqualTo(300);
        assertThat(threshold.category()).isEqualTo(ThermalCategory.REFRIGERATED_2_TO_8);
    }

    @Test
    @DisplayName("Should detect temperature excursion when reading exceeds upper or lower bound")
    void shouldDetectExcursions() {
        TemperatureThreshold threshold = TemperatureThreshold.forCategory(ThermalCategory.REFRIGERATED_2_TO_8, 300);

        assertThat(threshold.isExcursion(5.0)).isFalse();  // Within safe range
        assertThat(threshold.isExcursion(2.0)).isFalse();  // Lower bound exact match
        assertThat(threshold.isExcursion(8.0)).isFalse();  // Upper bound exact match

        assertThat(threshold.isExcursion(1.99)).isTrue();  // Below lower bound
        assertThat(threshold.isExcursion(8.01)).isTrue();  // Above upper bound
        assertThat(threshold.isExcursion(-5.0)).isTrue();  // Deep freeze breach
        assertThat(threshold.isExcursion(25.0)).isTrue();  // High heat breach
    }

    @Test
    @DisplayName("Should reject invalid threshold where min exceeds max")
    void shouldRejectMinGreaterThanMax() {
        assertThatThrownBy(() ->
            TemperatureThreshold.of(10.0, 5.0, 100, ThermalCategory.REFRIGERATED_2_TO_8)
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be greater than maxCelsius");
    }

    @Test
    @DisplayName("Should reject negative excursion seconds")
    void shouldRejectNegativeExcursionSeconds() {
        assertThatThrownBy(() ->
            TemperatureThreshold.of(2.0, 8.0, -1, ThermalCategory.REFRIGERATED_2_TO_8)
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxExcursionSeconds cannot be negative");
    }
}
