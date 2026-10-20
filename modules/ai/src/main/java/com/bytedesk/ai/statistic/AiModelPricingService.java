package com.bytedesk.ai.statistic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bytedesk.core.llm.LlmProviderConstants;

import lombok.RequiredArgsConstructor;

/**
 * Estimates token cost using the official DeepSeek V4 pricing that was
 * published on 2026-10-18. All rates are USD per one million tokens.
 *
 * DeepSeek prices input cache hits and misses differently. Spring AI currently
 * exposes aggregate prompt tokens, so the estimate conservatively uses the
 * cache-miss price unless the provider response explicitly supplies cache data.
 */
@Service
@RequiredArgsConstructor
public class AiModelPricingService {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final BigDecimal PEAK_MULTIPLIER = new BigDecimal("2");

    private final AiPricingProperties properties;

    public Quote quote(String provider, String model, Instant occurredAt) {
        if (!properties.isEnabled()) {
            return Quote.unavailable();
        }

        String normalizedProvider = normalize(provider);
        String normalizedModel = normalize(model);
        Instant effectiveTime = occurredAt != null ? occurredAt : Instant.now();
        boolean peak = isDeepSeekPeak(effectiveTime);

        Optional<AiPricingProperties.PriceOverride> override = findOverride(
                normalizedProvider, normalizedModel);
        if (override.isPresent()) {
            AiPricingProperties.PriceOverride value = override.get();
            if (value.getInputPerMillionUsd() == null || value.getOutputPerMillionUsd() == null) {
                return Quote.unavailable();
            }
            BigDecimal multiplier = peak && value.getPeakMultiplier() != null
                    ? value.getPeakMultiplier()
                    : BigDecimal.ONE;
            return new Quote(true, peak,
                    value.getInputPerMillionUsd().multiply(multiplier),
                    value.getOutputPerMillionUsd().multiply(multiplier),
                    properties.getVersion(), false);
        }

        if (LlmProviderConstants.DEEPSEEK.equalsIgnoreCase(normalizedProvider)) {
            return deepSeekQuote(normalizedModel, peak);
        }
        return Quote.unavailable();
    }

    public Cost calculate(Quote quote, long promptTokens, long completionTokens) {
        if (quote == null || !quote.available()) {
            return Cost.zero(properties.getUsdToCny());
        }
        BigDecimal inputCost = BigDecimal.valueOf(Math.max(0L, promptTokens))
                .divide(ONE_MILLION, 16, RoundingMode.HALF_UP)
                .multiply(quote.inputPerMillionUsd());
        BigDecimal outputCost = BigDecimal.valueOf(Math.max(0L, completionTokens))
                .divide(ONE_MILLION, 16, RoundingMode.HALF_UP)
                .multiply(quote.outputPerMillionUsd());
        BigDecimal totalUsd = inputCost.add(outputCost).setScale(8, RoundingMode.HALF_UP);
        BigDecimal totalCny = totalUsd.multiply(properties.getUsdToCny())
                .setScale(8, RoundingMode.HALF_UP);
        return new Cost(
                inputCost.setScale(8, RoundingMode.HALF_UP),
                outputCost.setScale(8, RoundingMode.HALF_UP),
                totalUsd,
                totalCny);
    }

    private Quote deepSeekQuote(String model, boolean peak) {
        if ("deepseek-flash".equals(model) || "deepseek-v4-flash".equals(model)) {
            return deepSeekRates(peak, "0.15", "0.60");
        }
        if ("deepseek-v4-pro".equals(model) || "deepseek-pro".equals(model)) {
            return deepSeekRates(peak, "0.66", "1.98");
        }
        return Quote.unavailable();
    }

    private Quote deepSeekRates(boolean peak, String inputOffPeak, String outputOffPeak) {
        BigDecimal multiplier = peak ? PEAK_MULTIPLIER : BigDecimal.ONE;
        return new Quote(true, peak,
                new BigDecimal(inputOffPeak).multiply(multiplier),
                new BigDecimal(outputOffPeak).multiply(multiplier),
                properties.getVersion(), true);
    }

    private boolean isDeepSeekPeak(Instant instant) {
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        LocalDate date = utc.toLocalDate();
        if (properties.getDeepseekOffPeakDates() != null
                && properties.getDeepseekOffPeakDates().contains(date.toString())) {
            return false;
        }
        if (utc.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        LocalTime time = utc.toLocalTime();
        return !time.isBefore(LocalTime.of(1, 0)) && time.isBefore(LocalTime.of(4, 0))
                || !time.isBefore(LocalTime.of(6, 0)) && time.isBefore(LocalTime.of(10, 0));
    }

    private Optional<AiPricingProperties.PriceOverride> findOverride(String provider, String model) {
        if (properties.getOverrides() == null) {
            return Optional.empty();
        }
        return properties.getOverrides().stream()
                .filter(item -> provider.equals(normalize(item.getProvider())))
                .filter(item -> !StringUtils.hasText(item.getModel())
                        || model.equals(normalize(item.getModel())))
                .findFirst();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Quote(boolean available, boolean peak, BigDecimal inputPerMillionUsd,
            BigDecimal outputPerMillionUsd, String pricingVersion, boolean cacheMissAssumed) {
        public static Quote unavailable() {
            return new Quote(false, false, BigDecimal.ZERO, BigDecimal.ZERO, "unpriced", false);
        }
    }

    public record Cost(BigDecimal inputCostUsd, BigDecimal outputCostUsd,
            BigDecimal totalCostUsd, BigDecimal totalCostCny) {
        public static Cost zero(BigDecimal usdToCny) {
            return new Cost(BigDecimal.ZERO.setScale(8), BigDecimal.ZERO.setScale(8),
                    BigDecimal.ZERO.setScale(8), BigDecimal.ZERO.setScale(8));
        }
    }
}
