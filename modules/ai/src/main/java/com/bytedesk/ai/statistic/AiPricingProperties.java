package com.bytedesk.ai.statistic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Configurable model pricing. Rates are USD per one million tokens.
 */
@Data
@Component
@ConfigurationProperties(prefix = "bytedesk.ai.pricing")
public class AiPricingProperties {

    private boolean enabled = true;

    private String version = "deepseek-official-2026-10-18";

    private BigDecimal usdToCny = new BigDecimal("6.6985");

    /**
     * Dates in UTC or Asia/Shanghai that should be treated as off-peak even if
     * they fall on a weekday. Format: yyyy-MM-dd.
     */
    private List<String> deepseekOffPeakDates = new ArrayList<>();

    /**
     * Optional provider/model overrides. They take precedence over built-in
     * pricing and let operators update prices without a code release.
     */
    private List<PriceOverride> overrides = new ArrayList<>();

    @Data
    public static class PriceOverride {
        private String provider;
        private String model;
        private BigDecimal inputPerMillionUsd;
        private BigDecimal outputPerMillionUsd;
        private BigDecimal peakMultiplier = new BigDecimal("2");
    }
}
