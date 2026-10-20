package com.bytedesk.ai.statistic;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.bytedesk.ai.springai.event.LlmTokenUsageEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiTokenUsageEventListener {

    private final AiTokenUsageService usageService;

    @EventListener
    @Async("applicationTaskExecutor")
    public void onTokenUsage(LlmTokenUsageEvent event) {
        try {
            usageService.record(event);
        } catch (Exception exception) {
            // Usage accounting must never break the user's AI response.
            log.error("Failed to persist AI token usage for org={}, provider={}, model={}",
                    event.getOrgUid(), event.getAiProvider(), event.getAiModelType(), exception);
        }
    }
}
