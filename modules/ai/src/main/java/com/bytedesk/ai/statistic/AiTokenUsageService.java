package com.bytedesk.ai.statistic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bytedesk.ai.springai.event.LlmTokenUsageEvent;
import com.bytedesk.core.uid.UidUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiTokenUsageService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final AiTokenUsageRepository repository;
    private final AiModelPricingService pricingService;
    private final AiPricingProperties pricingProperties;
    private final UidUtils uidUtils;

    @Transactional
    public AiTokenUsageEntity record(LlmTokenUsageEvent event) {
        Instant occurredAt = Instant.ofEpochMilli(event.getTimestamp());
        AiModelPricingService.Quote quote = pricingService.quote(
                event.getAiProvider(), event.getAiModelType(), occurredAt);
        AiModelPricingService.Cost cost = pricingService.calculate(
                quote, event.getPromptTokens(), event.getCompletionTokens());

        AiTokenUsageEntity usage = AiTokenUsageEntity.builder()
                .uid(uidUtils.getUid())
                .orgUid(event.getOrgUid())
                .provider(event.getAiProvider())
                .model(event.getAiModelType())
                .robotUid(event.getRobotUid())
                .usageType("CHAT")
                .promptTokens(event.getPromptTokens())
                .completionTokens(event.getCompletionTokens())
                .totalTokens(event.getPromptTokens() + event.getCompletionTokens())
                .success(event.isSuccess())
                .responseTimeMs(event.getResponseTime())
                .inputCostUsd(cost.inputCostUsd())
                .outputCostUsd(cost.outputCostUsd())
                .totalCostUsd(cost.totalCostUsd())
                .totalCostCny(cost.totalCostCny())
                .inputPricePerMillionUsd(quote.inputPerMillionUsd())
                .outputPricePerMillionUsd(quote.outputPerMillionUsd())
                .peakPricing(quote.peak())
                .cacheMissAssumed(quote.cacheMissAssumed())
                .pricingAvailable(quote.available())
                .pricingVersion(quote.pricingVersion())
                .createdAt(ZonedDateTime.ofInstant(occurredAt, BUSINESS_ZONE))
                .build();
        return repository.save(usage);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(String orgUid, ZonedDateTime start, ZonedDateTime end) {
        Totals total = totals(repository.summarizeAll(orgUid));
        Totals today = totals(repository.summarizePeriod(orgUid, start, end));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRequests", total.requests());
        result.put("todayRequests", today.requests());
        result.put("totalPromptTokens", total.promptTokens());
        result.put("todayPromptTokens", today.promptTokens());
        result.put("totalCompletionTokens", total.completionTokens());
        result.put("todayCompletionTokens", today.completionTokens());
        result.put("totalTokens", total.totalTokens());
        result.put("todayTokens", today.totalTokens());
        result.put("totalCostUsd", total.costUsd());
        result.put("todayCostUsd", today.costUsd());
        result.put("totalCostCny", total.costCny());
        result.put("todayCostCny", today.costCny());
        result.put("pricedRequests", total.pricedRequests());
        result.put("unpricedRequests", total.unpricedRequests());
        result.put("costAvailable", total.requests() == 0 || total.unpricedRequests() == 0);
        result.put("hasData", total.requests() > 0);
        result.put("pricing", pricingInfo());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> hourly(String orgUid, LocalDate date) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now(BUSINESS_ZONE);
        ZonedDateTime start = effectiveDate.atStartOfDay(BUSINESS_ZONE);
        ZonedDateTime end = start.plusDays(1);
        List<AiTokenUsageEntity> rows = repository
                .findByOrgUidAndCreatedAtBetweenAndDeletedFalseOrderByCreatedAtAsc(
                        orgUid, start, end);

        List<Map<String, Object>> buckets = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("hour", hour);
            bucket.put("requests", 0L);
            bucket.put("failedRequests", 0L);
            bucket.put("promptTokens", 0L);
            bucket.put("completionTokens", 0L);
            bucket.put("totalTokens", 0L);
            bucket.put("totalCostUsd", BigDecimal.ZERO.setScale(8));
            bucket.put("totalCostCny", BigDecimal.ZERO.setScale(8));
            buckets.add(bucket);
        }

        for (AiTokenUsageEntity row : rows) {
            int hour = row.getCreatedAt().withZoneSameInstant(BUSINESS_ZONE).getHour();
            Map<String, Object> bucket = buckets.get(hour);
            bucket.put("requests", number(bucket.get("requests")) + 1);
            if (!row.isSuccess()) {
                bucket.put("failedRequests", number(bucket.get("failedRequests")) + 1);
            }
            bucket.put("promptTokens", number(bucket.get("promptTokens")) + row.getPromptTokens());
            bucket.put("completionTokens",
                    number(bucket.get("completionTokens")) + row.getCompletionTokens());
            bucket.put("totalTokens", number(bucket.get("totalTokens")) + row.getTotalTokens());
            bucket.put("totalCostUsd", decimal(bucket.get("totalCostUsd")).add(row.getTotalCostUsd()));
            bucket.put("totalCostCny", decimal(bucket.get("totalCostCny")).add(row.getTotalCostCny()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("organizationUid", orgUid);
        result.put("date", effectiveDate.toString());
        result.put("hourlyData", buckets);
        result.put("pricing", pricingInfo());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> modelBreakdown(String orgUid) {
        List<Map<String, Object>> models = new ArrayList<>();
        for (Object[] row : repository.groupByModel(orgUid)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", string(row, 0));
            item.put("model", string(row, 1));
            item.put("requests", number(value(row, 2)));
            item.put("promptTokens", number(value(row, 3)));
            item.put("completionTokens", number(value(row, 4)));
            item.put("totalTokens", number(value(row, 5)));
            item.put("totalCostUsd", decimal(value(row, 6)));
            item.put("totalCostCny", decimal(value(row, 7)));
            models.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", models);
        result.put("pricing", pricingInfo());
        return result;
    }

    public Map<String, Object> pricingInfo() {
        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("version", pricingProperties.getVersion());
        pricing.put("usdToCny", pricingProperties.getUsdToCny());
        pricing.put("estimated", true);
        pricing.put("cachePricing", "CACHE_MISS_ASSUMED");
        pricing.put("note",
                "DeepSeek cost uses official input/output rates and peak/off-peak pricing. "
                        + "Prompt tokens are conservatively priced as cache misses; CNY is converted "
                        + "with the configured exchange rate.");
        return pricing;
    }

    private Totals totals(AiTokenUsageTotals row) {
        long requests = row == null || row.getRequests() == null ? 0L : row.getRequests();
        long promptTokens = row == null || row.getPromptTokens() == null ? 0L : row.getPromptTokens();
        long completionTokens = row == null || row.getCompletionTokens() == null
                ? 0L
                : row.getCompletionTokens();
        long totalTokens = row == null || row.getTotalTokens() == null ? 0L : row.getTotalTokens();
        BigDecimal costUsd = decimal(row == null ? null : row.getCostUsd());
        BigDecimal costCny = decimal(row == null ? null : row.getCostCny());
        long pricedRequests = row == null || row.getPricedRequests() == null
                ? 0L
                : row.getPricedRequests();
        return new Totals(requests, promptTokens, completionTokens, totalTokens,
                costUsd, costCny, pricedRequests, Math.max(0L, requests - pricedRequests));
    }

    private Object value(Object[] row, int index) {
        return row != null && row.length > index ? row[index] : null;
    }

    private String string(Object[] row, int index) {
        Object value = value(row, index);
        return value == null ? "" : String.valueOf(value);
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(8, RoundingMode.HALF_UP);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(8, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(8);
    }

    private record Totals(long requests, long promptTokens, long completionTokens,
            long totalTokens, BigDecimal costUsd, BigDecimal costCny,
            long pricedRequests, long unpricedRequests) {
    }
}
