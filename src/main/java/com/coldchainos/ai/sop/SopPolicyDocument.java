package com.coldchainos.ai.sop;

import java.math.BigDecimal;

/**
 * Standard Operating Procedure (SOP) specification capturing pharmaceutical stability guidelines.
 */
public record SopPolicyDocument(
    String sopId,
    String cargoType,
    String title,
    String section,
    String textContent,
    BigDecimal recommendedMinCelsius,
    BigDecimal recommendedMaxCelsius,
    BigDecimal allowableExcursionMaxCelsius,
    int maxAllowedExcursionMinutes
) {}
