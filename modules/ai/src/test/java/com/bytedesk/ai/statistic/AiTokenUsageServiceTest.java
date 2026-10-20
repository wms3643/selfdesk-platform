package com.bytedesk.ai.statistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bytedesk.ai.springai.event.LlmTokenUsageEvent;
import com.bytedesk.core.uid.UidUtils;

class AiTokenUsageServiceTest {

    @Test
    void persistsTokensAndThePriceUsedAtCallTime() {
        AiTokenUsageRepository repository = mock(AiTokenUsageRepository.class);
        AiModelPricingService pricing = mock(AiModelPricingService.class);
        AiPricingProperties properties = new AiPricingProperties();
        UidUtils uidUtils = mock(UidUtils.class);
        when(uidUtils.getUid()).thenReturn("usage-1");
        when(pricing.quote(eq("deepseek"), eq("deepseek-flash"), any()))
                .thenReturn(new AiModelPricingService.Quote(true, false,
                        new BigDecimal("0.15"), new BigDecimal("0.60"),
                        "deepseek-official-2026-10-18", true));
        when(pricing.calculate(any(), anyLong(), anyLong()))
                .thenReturn(new AiModelPricingService.Cost(
                        new BigDecimal("0.15"), new BigDecimal("0.60"),
                        new BigDecimal("0.75"), new BigDecimal("5.40")));
        when(repository.save(any(AiTokenUsageEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AiTokenUsageService service = new AiTokenUsageService(
                repository, pricing, properties, uidUtils);
        LlmTokenUsageEvent event = new LlmTokenUsageEvent(
                this, "org-1", "robot-1", "deepseek", "deepseek-flash",
                1_000_000, 1_000_000, true, 850, BigDecimal.ZERO);
        service.record(event);

        ArgumentCaptor<AiTokenUsageEntity> captor = ArgumentCaptor.forClass(AiTokenUsageEntity.class);
        verify(repository).save(captor.capture());
        AiTokenUsageEntity saved = captor.getValue();
        assertEquals("org-1", saved.getOrgUid());
        assertEquals("robot-1", saved.getRobotUid());
        assertEquals(2_000_000, saved.getTotalTokens());
        assertEquals(0, new BigDecimal("0.75").compareTo(saved.getTotalCostUsd()));
        assertEquals(0, new BigDecimal("5.40").compareTo(saved.getTotalCostCny()));
        assertTrue(saved.isPricingAvailable());
    }

    @Test
    void summarySeparatesTodayFromAllTime() {
        AiTokenUsageRepository repository = mock(AiTokenUsageRepository.class);
        AiModelPricingService pricing = mock(AiModelPricingService.class);
        AiPricingProperties properties = new AiPricingProperties();
        UidUtils uidUtils = mock(UidUtils.class);
        when(repository.summarizeAll("org-1")).thenReturn(new AiTokenUsageTotals(
                10L, 1000L, 500L, 1500L, new BigDecimal("0.01"),
                new BigDecimal("0.072"), 10L));
        when(repository.summarizePeriod(eq("org-1"), any(), any()))
                .thenReturn(new AiTokenUsageTotals(
                        2L, 200L, 100L, 300L, new BigDecimal("0.002"),
                        new BigDecimal("0.0144"), 2L));

        AiTokenUsageService service = new AiTokenUsageService(
                repository, pricing, properties, uidUtils);
        ZonedDateTime start = LocalDate.of(2026, 10, 18)
                .atStartOfDay(ZoneId.of("Asia/Shanghai"));
        Map<String, Object> result = service.summary("org-1", start, start.plusDays(1));

        assertEquals(10L, result.get("totalRequests"));
        assertEquals(1500L, result.get("totalTokens"));
        assertEquals(300L, result.get("todayTokens"));
        assertEquals(0, ((BigDecimal) result.get("todayCostCny"))
                .compareTo(new BigDecimal("0.0144")));
        assertTrue((Boolean) result.get("costAvailable"));
    }
}
