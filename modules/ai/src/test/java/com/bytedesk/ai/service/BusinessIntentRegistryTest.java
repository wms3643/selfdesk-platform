package com.bytedesk.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class BusinessIntentRegistryTest {

    @Test
    void detectsCompoundOrderAndComplaintIntents() {
        BusinessIntentResult result = BusinessIntentRegistry.classifyByRules("订单A123456三天没发货，我要投诉");

        Set<String> types = result.getIntents().stream()
                .map(BusinessIntentMatch::getType)
                .collect(Collectors.toSet());
        assertTrue(types.contains(BusinessIntentType.ORDER_QUERY.name()));
        assertTrue(types.contains(BusinessIntentType.LOGISTICS_QUERY.name()));
        assertTrue(types.contains(BusinessIntentType.COMPLAINT.name()));
        assertEquals(BusinessIntentAction.TRANSFER_HUMAN,
                BusinessIntentRegistry.evaluate(result).action());
    }

    @Test
    void orderQueryWithOneIdentifierRoutesToToolPlan() {
        BusinessIntentResult result = BusinessIntentRegistry.classifyByRules("订单A123456什么时候发货");
        BusinessIntentEvaluation evaluation = BusinessIntentRegistry.evaluate(result);

        assertEquals(BusinessIntentAction.TOOL, evaluation.action());
        assertTrue(evaluation.intents().stream()
                .flatMap(match -> match.getSlots().stream())
                .anyMatch(slot -> "orderNo".equals(slot.getName()) && "A123456".equals(slot.getValue())));
    }

    @Test
    void missingOrderIdentifierProducesClarification() {
        BusinessIntentEvaluation evaluation = BusinessIntentRegistry.evaluate(
                BusinessIntentRegistry.classifyByRules("我想查订单"));

        assertEquals(BusinessIntentAction.CLARIFY, evaluation.action());
        assertTrue(evaluation.missingSlots().contains("orderNo|phone"));
        assertTrue(evaluation.clarificationQuestion().contains("订单号"));
    }

    @Test
    void shortComplaintProducesClarificationInsteadOfBlindTransfer() {
        BusinessIntentEvaluation evaluation = BusinessIntentRegistry.evaluate(
                BusinessIntentRegistry.classifyByRules("我要投诉"));

        assertEquals(BusinessIntentAction.CLARIFY, evaluation.action());
        assertTrue(evaluation.missingSlots().contains("reason"));
    }

    @Test
    void completeOrderCreationRoutesToToolWithRequiredSlots() {
        BusinessIntentResult result = BusinessIntentRegistry.classifyByRules(
                "我要下单购买 示例蓝牙耳机，数量1件，电话13800138000，地址杭州市西湖区");
        BusinessIntentEvaluation evaluation = BusinessIntentRegistry.evaluate(result);
        BusinessIntentMatch orderCreate = result.getIntents().stream()
                .filter(intent -> BusinessIntentType.ORDER_CREATE.name().equals(intent.getType()))
                .findFirst()
                .orElseThrow();

        assertEquals(BusinessIntentAction.TOOL, evaluation.action());
        assertEquals("示例蓝牙耳机", slotValue(orderCreate, "productName"));
        assertEquals("1", slotValue(orderCreate, "quantity"));
        assertEquals("13800138000", slotValue(orderCreate, "phone"));
        assertEquals("杭州市西湖区", slotValue(orderCreate, "address"));
    }

    private static String slotValue(BusinessIntentMatch intent, String name) {
        return intent.getSlots().stream()
                .filter(slot -> name.equals(slot.getName()))
                .map(BusinessSlot::getValue)
                .findFirst()
                .orElse(null);
    }

    @Test
    void refundUsesKnowledgeBeforeAnswering() {
        BusinessIntentResult result = BusinessIntentRegistry.classifyByRules("我想问一下退款流程");

        assertEquals(BusinessIntentAction.KNOWLEDGE_RAG,
                BusinessIntentRegistry.evaluate(result).action());
    }

    @Test
    void explicitOrderCreationWithoutDetailsRequestsRequiredSlots() {
        BusinessIntentResult result = BusinessIntentRegistry.classifyByRules("我想创建一个订单");
        BusinessIntentEvaluation evaluation = BusinessIntentRegistry.evaluate(result);

        assertTrue(result.getIntents().stream()
                .anyMatch(intent -> BusinessIntentType.ORDER_CREATE.name().equals(intent.getType())));
        assertEquals(BusinessIntentAction.CLARIFY, evaluation.action());
        assertTrue(evaluation.missingSlots().contains("productName"));
        assertTrue(evaluation.missingSlots().contains("quantity"));
        assertTrue(evaluation.missingSlots().contains("phone"));
        assertTrue(evaluation.missingSlots().contains("address"));
    }

    @Test
    void orderCreationHowToQuestionUsesKnowledgeInsteadOfStartingAnOrder() {
        BusinessIntentResult result = BusinessIntentRegistry.classifyByRules("请问怎么下单");

        assertTrue(result.getIntents().stream()
                .noneMatch(intent -> BusinessIntentType.ORDER_CREATE.name().equals(intent.getType())));
    }

    @Test
    void modelResultIsNormalizedAndSanitized() {
        BusinessIntentResult raw = new BusinessIntentResult(
                "order_query",
                List.of(new BusinessIntentMatch("order_query", 95.0,
                        List.of(new BusinessSlot("order_no", "A123456")))),
                "model");

        BusinessIntentResult sanitized = BusinessIntentRegistry.sanitizeModelResult(raw);
        BusinessIntentMatch match = sanitized.getIntents().get(0);

        assertEquals(BusinessIntentType.ORDER_QUERY.name(), match.getType());
        assertEquals(0.95, match.getConfidence(), 0.001);
        assertEquals("orderNo", match.getSlots().get(0).getName());
    }

    @Test
    void slotExtractionUsesOnlyTheSelectedIntentSchema() {
        BusinessIntentDefinition definition = BusinessIntentRegistry.definitionFor("ORDER_QUERY");
        String prompt = BusinessIntentRegistry.buildSlotExtractionPrompt(
                definition, "用户: 订单A123456什么时候发货", "{}");

        assertTrue(prompt.contains("ORDER_QUERY"));
        assertTrue(prompt.contains("orderNo"));
        assertTrue(!prompt.contains("LOGISTICS_QUERY"));

        BusinessSlotResult raw = new BusinessSlotResult("ORDER_QUERY",
                List.of(new BusinessSlot("order_no", "A123456"), new BusinessSlot("trackingNo", "SF123")),
                "test");
        List<BusinessSlot> slots = BusinessIntentRegistry.sanitizeSlotResult(
                raw, BusinessIntentType.ORDER_QUERY);
        assertEquals(1, slots.size());
        assertEquals("orderNo", slots.get(0).getName());
    }

    @Test
    void lowConfidenceIntentIsNotUsedForRouting() {
        BusinessIntentResult raw = new BusinessIntentResult(
                "ORDER_QUERY",
                List.of(new BusinessIntentMatch("ORDER_QUERY", 0.40,
                        List.of(new BusinessSlot("orderNo", "A123456")))),
                "low-confidence");

        assertEquals(BusinessIntentAction.UNKNOWN,
                BusinessIntentRegistry.evaluate(raw).action());
    }

    @Test
    void runtimeThresholdOverrideCanTightenARoute() {
        BusinessIntentResult raw = new BusinessIntentResult(
                "ORDER_QUERY",
                List.of(new BusinessIntentMatch("ORDER_QUERY", 0.70,
                        List.of(new BusinessSlot("orderNo", "A123456")))),
                "test");

        assertEquals(BusinessIntentAction.TOOL,
                BusinessIntentRegistry.evaluate(raw).action());
        assertEquals(BusinessIntentAction.UNKNOWN,
                BusinessIntentRegistry.evaluate(raw, Map.of("ORDER_QUERY", 0.80)).action());
    }

    @Test
    void modelResultKeepsDeterministicRuleIntentsAndSlots() {
        BusinessIntentResult rules = BusinessIntentRegistry.classifyByRules(
                "订单A123456三天没发货，我要投诉");
        BusinessIntentResult model = new BusinessIntentResult(
                "LOGISTICS_QUERY",
                List.of(new BusinessIntentMatch("LOGISTICS_QUERY", 0.91, List.of())),
                "model");

        BusinessIntentResult merged = BusinessIntentRegistry.mergeModelWithRules(model, rules);
        BusinessIntentEvaluation evaluation = BusinessIntentRegistry.evaluate(merged);

        assertEquals(BusinessIntentAction.TRANSFER_HUMAN, evaluation.action());
        assertTrue(evaluation.intents().stream().anyMatch(intent -> "COMPLAINT".equals(intent.getType())));
        assertTrue(evaluation.intents().stream()
                .flatMap(intent -> intent.getSlots().stream())
                .anyMatch(slot -> "orderNo".equals(slot.getName()) && "A123456".equals(slot.getValue())));
    }

    @Test
    void configuredIntentListMapsBackendLabelsAndFiltersDisabledIntents() {
        Set<String> enabled = BusinessIntentRegistry.configuredTypes(
                List.of("订单查询", "物流查询", "投诉反馈", "其他"));
        BusinessIntentResult result = BusinessIntentRegistry.classifyByRules(
                "订单A123456没发货，我要投诉");
        BusinessIntentResult filtered = BusinessIntentRegistry.filterEnabledIntents(result, enabled);

        assertEquals(Set.of("ORDER_QUERY", "LOGISTICS_QUERY", "COMPLAINT", "UNKNOWN",
                "CASUAL", "HUMAN_TRANSFER"), enabled);
        assertTrue(filtered.getIntents().stream().anyMatch(match -> "COMPLAINT".equals(match.getType())));

        BusinessIntentResult orderOnly = BusinessIntentRegistry.filterEnabledIntents(result,
                Set.of("ORDER_QUERY"));
        assertEquals(1, orderOnly.getIntents().size());
        assertEquals("ORDER_QUERY", orderOnly.getIntents().get(0).getType());
    }
}
