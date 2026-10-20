package com.bytedesk.ai.service;

import java.util.List;

public record BusinessIntentEvaluation(
        List<BusinessIntentMatch> intents,
        BusinessIntentAction action,
        List<String> missingSlots,
        String clarificationQuestion,
        String reason) {

    public boolean hasAction(BusinessIntentAction expected) {
        return action == expected;
    }

    public String intentSummary() {
        if (intents == null || intents.isEmpty()) {
            return "UNKNOWN";
        }
        return String.join(",", intents.stream()
                .map(BusinessIntentMatch::getType)
                .toList());
    }
}
