package com.bytedesk.ai.statistic;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Explicit JPQL constructor projection for aggregate usage queries.
 */
@Getter
@AllArgsConstructor
public class AiTokenUsageTotals {

    private final Long requests;
    private final Long promptTokens;
    private final Long completionTokens;
    private final Long totalTokens;
    private final BigDecimal costUsd;
    private final BigDecimal costCny;
    private final Long pricedRequests;
}
