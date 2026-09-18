package com.coldchainos.shared.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "coldchain.rate-limit")
public class RateLimitProperties {

    /**
     * Default maximum burst capacity (number of tokens) in the bucket per tenant.
     */
    private long defaultCapacity = 10;

    /**
     * Default replenishment rate (tokens refilled per second) per tenant.
     */
    private long defaultRefillRatePerSecond = 5;

    public long getDefaultCapacity() {
        return defaultCapacity;
    }

    public void setDefaultCapacity(long defaultCapacity) {
        this.defaultCapacity = defaultCapacity;
    }

    public long getDefaultRefillRatePerSecond() {
        return defaultRefillRatePerSecond;
    }

    public void setDefaultRefillRatePerSecond(long defaultRefillRatePerSecond) {
        this.defaultRefillRatePerSecond = defaultRefillRatePerSecond;
    }
}
