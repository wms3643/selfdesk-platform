package com.bytedesk.ai.service;

import java.util.List;

public record BusinessIntentDefinition(
        BusinessIntentType type,
        String description,
        List<String> keywords,
        List<BusinessSlotDefinition> slots,
        boolean needsKnowledge,
        BusinessIntentAction action) {
}
