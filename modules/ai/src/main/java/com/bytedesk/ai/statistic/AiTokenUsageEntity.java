package com.bytedesk.ai.statistic;

import java.math.BigDecimal;

import com.bytedesk.core.base.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(name = "bytedesk_ai_token_usage")
@Table(name = "bytedesk_ai_token_usage", indexes = {
        @jakarta.persistence.Index(name = "idx_ai_token_usage_org_created",
                columnList = "org_uid, created_at"),
        @jakarta.persistence.Index(name = "idx_ai_token_usage_org_model",
                columnList = "org_uid, provider, model")
})
public class AiTokenUsageEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "robot_uid", length = 64)
    private String robotUid;

    @Column(name = "usage_type", nullable = false, length = 32)
    @Builder.Default
    private String usageType = "CHAT";

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "response_time_ms")
    private long responseTimeMs;

    @Column(name = "input_cost_usd", precision = 18, scale = 8, nullable = false)
    @Builder.Default
    private BigDecimal inputCostUsd = BigDecimal.ZERO;

    @Column(name = "output_cost_usd", precision = 18, scale = 8, nullable = false)
    @Builder.Default
    private BigDecimal outputCostUsd = BigDecimal.ZERO;

    @Column(name = "total_cost_usd", precision = 18, scale = 8, nullable = false)
    @Builder.Default
    private BigDecimal totalCostUsd = BigDecimal.ZERO;

    @Column(name = "total_cost_cny", precision = 18, scale = 8, nullable = false)
    @Builder.Default
    private BigDecimal totalCostCny = BigDecimal.ZERO;

    @Column(name = "input_price_per_million_usd", precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal inputPricePerMillionUsd = BigDecimal.ZERO;

    @Column(name = "output_price_per_million_usd", precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal outputPricePerMillionUsd = BigDecimal.ZERO;

    @Column(name = "peak_pricing")
    private boolean peakPricing;

    @Column(name = "cache_miss_assumed")
    private boolean cacheMissAssumed;

    @Column(name = "pricing_available", nullable = false)
    private boolean pricingAvailable;

    @Column(name = "pricing_version", length = 64)
    private String pricingVersion;
}
