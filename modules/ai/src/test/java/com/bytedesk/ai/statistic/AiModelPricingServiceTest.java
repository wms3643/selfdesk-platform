package com.bytedesk.ai.statistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AiModelPricingServiceTest {

    private final AiPricingProperties properties = new AiPricingProperties();
    private final AiModelPricingService service = new AiModelPricingService(properties);

    @Test
    void usesPeakRateForDeepSeekFlashOnWeekday() {
        AiModelPricingService.Quote quote = service.quote(
                "deepseek", "deepseek-flash", Instant.parse("2026-10-19T02:00:00Z"));
        AiModelPricingService.Cost cost = service.calculate(quote, 1_000_000, 1_000_000);

        assertTrue(quote.available());
        assertTrue(quote.peak());
        assertEquals(0, new BigDecimal("1.50000000").compareTo(cost.totalCostUsd()));
        assertEquals(0, new BigDecimal("10.04775000").compareTo(cost.totalCostCny()));
    }

    @Test
    void usesHalfPriceAtOffPeakWeekend() {
        AiModelPricingService.Quote quote = service.quote(
                "deepseek", "deepseek-v4-flash", Instant.parse("2026-10-18T02:00:00Z"));
        AiModelPricingService.Cost cost = service.calculate(quote, 1_000_000, 1_000_000);

        assertTrue(quote.available());
        assertFalse(quote.peak());
        assertEquals(0, new BigDecimal("0.75000000").compareTo(cost.totalCostUsd()));
    }

    @Test
    void unknownModelRemainsVisibleButUnpriced() {
        AiModelPricingService.Quote quote = service.quote(
                "deepseek", "future-model", Instant.parse("2026-10-18T02:00:00Z"));

        assertFalse(quote.available());
        assertEquals("unpriced", quote.pricingVersion());
    }
}
