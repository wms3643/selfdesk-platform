package com.bytedesk.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import com.bytedesk.ai.service.KnowledgeIntentRouter.Intent;

class KnowledgeIntentRouterTest {

    @Test
    void greetingAndThanksAreCasual() {
        assertEquals(Intent.CASUAL, KnowledgeIntentRouter.classifyByRules("你好，谢谢你").intent());
        assertEquals(Intent.CASUAL, KnowledgeIntentRouter.classifyByRules("hello there").intent());
        assertEquals(Intent.CASUAL, KnowledgeIntentRouter.classifyByRules("讲个笑话").intent());
        assertEquals(Intent.CASUAL, KnowledgeIntentRouter.classifyByRules("你觉得怎么样").intent());
    }

    @Test
    void greetingWithBusinessQuestionRequiresKnowledge() {
        assertEquals(Intent.KNOWLEDGE,
                KnowledgeIntentRouter.classifyByRules("你好，退款一般多久到账").intent());
        assertEquals(Intent.KNOWLEDGE,
                KnowledgeIntentRouter.classifyByRules("我想查一下订单状态").intent());
    }

    @Test
    void questionWithoutKnownBusinessKeywordStillPrefersKnowledge() {
        assertEquals(Intent.KNOWLEDGE, KnowledgeIntentRouter.classifyByRules("这个怎么弄？").intent());
        assertEquals(Intent.KNOWLEDGE, KnowledgeIntentRouter.classifyByRules("多久能好").intent());
    }

    @Test
    void ambiguousMessagesDefaultToKnowledge() {
        KnowledgeIntentRouter.Decision decision = KnowledgeIntentRouter.classifyByRules("帮我看看");
        assertEquals(Intent.AMBIGUOUS, decision.intent());
        assertEquals(Intent.KNOWLEDGE, KnowledgeIntentRouter.defaultKnowledge("no-model").intent());
    }

    @Test
    void modelResultIsSanitizedToKnownRoutes() {
        KnowledgeIntentRouter.Decision casual = KnowledgeIntentRouter.fromModel("chitchat", 88.0, "greeting");
        assertEquals(Intent.CASUAL, casual.intent());
        assertEquals(0.88, casual.confidence(), 0.001);
        assertEquals(Intent.KNOWLEDGE,
                KnowledgeIntentRouter.fromModel("知识问答", 0.8, "business").intent());
        assertEquals(Intent.KNOWLEDGE,
                KnowledgeIntentRouter.fromModel("unknown", 0.4, null).intent());
        assertTrue(KnowledgeIntentRouter.fromModel("knowledge", 0.8, "x".repeat(300)).reason().length() <= 200);
    }

    @Test
    void resultTypeHasJsonSchemaForApiLevelStructuredOutput() {
        BeanOutputConverter<KnowledgeIntentResult> converter =
                new BeanOutputConverter<>(KnowledgeIntentResult.class);
        String schema = converter.getJsonSchema();

        assertTrue(schema.contains("intent"));
        assertTrue(schema.contains("confidence"));
        assertTrue(schema.contains("reason"));
    }
}
