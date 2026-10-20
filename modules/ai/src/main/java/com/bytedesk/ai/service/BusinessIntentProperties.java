package com.bytedesk.ai.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "bytedesk.ai.business-intent")
public class BusinessIntentProperties {

    /**
     * Per-intent confidence thresholds. Keys are BusinessIntentType names.
     */
    private Map<String, Double> thresholds = new HashMap<>();

    /**
     * Maximum number of second-stage slot extraction calls for one user turn.
     */
    private int maxSlotExtractionCalls = 3;
}
