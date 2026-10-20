package com.bytedesk.ai.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class BusinessIntentRegistry {

    private static final Pattern ORDER_NO_PATTERN = Pattern.compile(
            "\\b(?=[A-Za-z0-9_-]{6,64}\\b)(?=[A-Za-z0-9_-]*\\d)[A-Za-z0-9_-]+\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ORDER_CREATE_PRODUCT_PATTERN = Pattern.compile(
            "(?:下单(?:购买)?|购买|买)\\s*([^，,。；;\\n]{1,100}?)(?=(?:[，,。；;]|数量|电话|地址|$))");
    private static final Pattern ORDER_CREATE_QUANTITY_PATTERN = Pattern.compile(
            "(?:数量\\s*[:：]?\\s*)?(\\d{1,3})\\s*(?:件|个|台|套|份|只)");
    private static final Pattern ORDER_CREATE_ADDRESS_PATTERN = Pattern.compile(
            "(?:收货地址|地址)\\s*[:：]?\\s*([^，,。；;\\n]{2,200})");

    private static final List<BusinessIntentDefinition> DEFINITIONS = List.of(
            new BusinessIntentDefinition(BusinessIntentType.CASUAL, "闲聊、问候、感谢、机器人身份或能力",
                    List.of("你好", "您好", "hi", "hello", "谢谢", "感谢", "再见", "你是谁", "你会什么",
                            "讲个笑话", "聊聊天", "你觉得", "辛苦了"),
                    List.of(), false, BusinessIntentAction.DIRECT_LLM),
            new BusinessIntentDefinition(BusinessIntentType.PRODUCT_CONSULT, "产品、商品、功能咨询",
                    List.of("产品", "商品", "功能", "怎么用", "使用说明", "product", "feature"),
                    List.of(BusinessSlotDefinition.optional("productName", "产品/商品名称")),
                    true, BusinessIntentAction.KNOWLEDGE_RAG),
            new BusinessIntentDefinition(BusinessIntentType.PRICE_QUERY, "价格、费用、报价咨询",
                    List.of("价格", "多少钱", "费用", "收费", "报价", "price", "cost"),
                    List.of(BusinessSlotDefinition.optional("productName", "产品/商品名称"),
                            BusinessSlotDefinition.optional("quantity", "数量")),
                    true, BusinessIntentAction.KNOWLEDGE_RAG),
            new BusinessIntentDefinition(BusinessIntentType.ORDER_QUERY, "订单状态、订单信息查询",
                    List.of("订单", "单号", "购买记录", "order"),
                    List.of(BusinessSlotDefinition.optional("orderNo", "订单号"),
                            BusinessSlotDefinition.optional("phone", "下单手机号")),
                    false, BusinessIntentAction.TOOL),
            new BusinessIntentDefinition(BusinessIntentType.ORDER_CREATE, "用户明确要求创建、提交订单或购买商品",
                    List.of("帮我下单", "我要下单", "我想下单", "创建一个订单", "提交订单",
                            "帮我买", "我要买", "我想买", "购买一个", "购买一台", "create order"),
                    List.of(BusinessSlotDefinition.required("productName", "商品名称", 1, 200),
                            BusinessSlotDefinition.required("quantity", "数量", 1, 3),
                            BusinessSlotDefinition.required("phone", "联系电话", 11, 11),
                            BusinessSlotDefinition.required("address", "收货地址", 2, 300)),
                    false, BusinessIntentAction.TOOL),
            new BusinessIntentDefinition(BusinessIntentType.REFUND_QUERY, "退款、退货、换货",
                    List.of("退款", "退货", "换货", "退钱", "refund", "return"),
                    List.of(BusinessSlotDefinition.optional("orderNo", "订单号"),
                            BusinessSlotDefinition.optional("reason", "退款/退货原因")),
                    true, BusinessIntentAction.KNOWLEDGE_RAG),
            new BusinessIntentDefinition(BusinessIntentType.LOGISTICS_QUERY, "物流、快递、发货、签收查询",
                    List.of("物流", "快递", "发货", "到货", "签收", "跟踪", "tracking", "shipping"),
                    List.of(BusinessSlotDefinition.optional("orderNo", "订单号"),
                            BusinessSlotDefinition.optional("phone", "下单手机号"),
                            BusinessSlotDefinition.optional("trackingNo", "物流单号")),
                    false, BusinessIntentAction.TOOL),
            new BusinessIntentDefinition(BusinessIntentType.AFTER_SALES, "售后、维修、保修、故障处理",
                    List.of("售后", "维修", "保修", "坏了", "故障", "不能使用", "after sales"),
                    List.of(BusinessSlotDefinition.optional("orderNo", "订单号"),
                            BusinessSlotDefinition.optional("issue", "问题描述")),
                    true, BusinessIntentAction.KNOWLEDGE_RAG),
            new BusinessIntentDefinition(BusinessIntentType.PAYMENT_ISSUE, "支付、扣款、付款问题",
                    List.of("支付", "付款", "扣款", "支付失败", "payment"),
                    List.of(BusinessSlotDefinition.optional("orderNo", "订单号"),
                            BusinessSlotDefinition.optional("issue", "支付问题描述")),
                    true, BusinessIntentAction.KNOWLEDGE_RAG),
            new BusinessIntentDefinition(BusinessIntentType.ACCOUNT_ISSUE, "账号、登录、密码问题",
                    List.of("账号", "账户", "登录", "密码", "验证码", "account", "login", "password"),
                    List.of(BusinessSlotDefinition.optional("account", "账号"),
                            BusinessSlotDefinition.optional("issue", "账号问题描述")),
                    true, BusinessIntentAction.KNOWLEDGE_RAG),
            new BusinessIntentDefinition(BusinessIntentType.TECHNICAL_SUPPORT, "技术故障、报错、接入问题",
                    List.of("报错", "技术", "接入", "接口", "异常", "error", "technical"),
                    List.of(BusinessSlotDefinition.optional("issue", "技术问题描述")),
                    true, BusinessIntentAction.KNOWLEDGE_RAG),
            new BusinessIntentDefinition(BusinessIntentType.FEATURE_SUGGESTION, "功能建议、需求反馈",
                    List.of("建议", "功能建议", "希望增加", "需求", "suggestion"),
                    List.of(BusinessSlotDefinition.optional("feature", "功能建议")),
                    false, BusinessIntentAction.TRANSFER_HUMAN),
            new BusinessIntentDefinition(BusinessIntentType.PROMOTION, "优惠、折扣、活动咨询",
                    List.of("优惠", "折扣", "活动", "券", "promotion", "discount"),
                    List.of(BusinessSlotDefinition.optional("campaign", "活动名称")),
                    true, BusinessIntentAction.KNOWLEDGE_RAG),
            new BusinessIntentDefinition(BusinessIntentType.COMPLAINT, "投诉、不满、追责",
                    List.of("投诉", "不满", "差评", "追责", "太差", "骗人", "complaint"),
                    List.of(BusinessSlotDefinition.required("reason", "投诉原因", 6, 500),
                            BusinessSlotDefinition.optional("orderNo", "订单号"),
                            BusinessSlotDefinition.optional("severity", "严重程度")),
                    false, BusinessIntentAction.TRANSFER_HUMAN),
            new BusinessIntentDefinition(BusinessIntentType.HUMAN_TRANSFER, "明确要求人工客服",
                    List.of("转人工", "人工客服", "真人客服", "人工服务", "human", "agent"),
                    List.of(), false, BusinessIntentAction.TRANSFER_HUMAN));

    private static final Map<String, BusinessIntentDefinition> BY_TYPE = buildTypeMap();

    private BusinessIntentRegistry() {
    }

    public static List<BusinessIntentDefinition> definitions() {
        return DEFINITIONS;
    }

    public static BusinessIntentResult classifyByRules(String query) {
        String originalQuery = query != null ? query.trim() : "";
        String normalized = originalQuery.toLowerCase(Locale.ROOT);
        List<BusinessIntentMatch> matches = new ArrayList<>();
        for (BusinessIntentDefinition definition : DEFINITIONS) {
            if (definition.type() == BusinessIntentType.CASUAL) {
                continue;
            }
            if (containsAny(normalized, definition.keywords())) {
                BusinessIntentMatch match = new BusinessIntentMatch();
                match.setType(definition.type().name());
                match.setConfidence(0.78);
                match.setSlots(extractRuleSlots(originalQuery, definition.type()));
                matches.add(match);
            }
        }

        if (matches.isEmpty()) {
            BusinessIntentDefinition casual = BY_TYPE.get(BusinessIntentType.CASUAL.name());
            if (containsAny(normalized, casual.keywords())) {
                BusinessIntentMatch match = new BusinessIntentMatch();
                match.setType(BusinessIntentType.CASUAL.name());
                match.setConfidence(0.88);
                match.setSlots(List.of());
                matches.add(match);
            }
        }

        if (matches.isEmpty()) {
            BusinessIntentMatch unknown = new BusinessIntentMatch();
            unknown.setType(BusinessIntentType.UNKNOWN.name());
            unknown.setConfidence(0.3);
            unknown.setSlots(List.of());
            matches.add(unknown);
        }
        return createResult(matches, "rule-layer");
    }

    public static BusinessIntentResult sanitizeModelResult(BusinessIntentResult raw) {
        if (raw == null || raw.getIntents() == null || raw.getIntents().isEmpty()) {
            return null;
        }
        Map<String, BusinessIntentMatch> matches = new LinkedHashMap<>();
        for (BusinessIntentMatch candidate : raw.getIntents()) {
            if (candidate == null) {
                continue;
            }
            String type = normalizeType(candidate.getType());
            if (!StringUtils.hasText(type) || !BY_TYPE.containsKey(type)
                    || BusinessIntentType.UNKNOWN.name().equals(type)) {
                continue;
            }
            BusinessIntentMatch match = matches.computeIfAbsent(type, ignored -> {
                BusinessIntentMatch created = new BusinessIntentMatch();
                created.setType(type);
                created.setConfidence(0.0);
                created.setSlots(new ArrayList<>());
                return created;
            });
            match.setConfidence(Math.max(match.getConfidence(), normalizeConfidence(candidate.getConfidence())));
            match.setSlots(mergeSlots(match.getSlots(), normalizeSlots(candidate.getSlots())));
        }
        if (matches.isEmpty()) {
            return null;
        }
        return createResult(new ArrayList<>(matches.values()),
                StringUtils.hasText(raw.getReason()) ? raw.getReason().trim() : "model");
    }

    /**
     * Model output is useful for generalization, while deterministic rules are
     * authoritative for regex-extracted identifiers and high-risk intents. The
     * union prevents a successful model call with a failed slot extraction from
     * discarding an order number that the rule layer already found.
     */
    public static BusinessIntentResult mergeModelWithRules(BusinessIntentResult rawModel,
            BusinessIntentResult ruleResult) {
        BusinessIntentResult sanitizedModel = sanitizeModelResult(rawModel);
        if (sanitizedModel == null) {
            return ruleResult;
        }
        if (ruleResult == null || ruleResult.getIntents() == null || ruleResult.getIntents().isEmpty()) {
            return sanitizedModel;
        }

        Map<String, BusinessIntentMatch> merged = new LinkedHashMap<>();
        for (BusinessIntentMatch match : sanitizedModel.getIntents()) {
            merged.put(match.getType(), copyMatch(match));
        }
        for (BusinessIntentMatch ruleMatch : ruleResult.getIntents()) {
            if (ruleMatch == null || !StringUtils.hasText(ruleMatch.getType())
                    || BusinessIntentType.UNKNOWN.name().equalsIgnoreCase(ruleMatch.getType())) {
                continue;
            }
            String type = normalizeType(ruleMatch.getType());
            BusinessIntentMatch existing = merged.get(type);
            if (existing == null) {
                merged.put(type, copyMatch(ruleMatch));
            } else {
                existing.setSlots(mergeSlots(existing.getSlots(), ruleMatch.getSlots()));
            }
        }
        return createResult(new ArrayList<>(merged.values()), "model-with-rule-guardrails");
    }

    public static BusinessIntentEvaluation evaluate(BusinessIntentResult result) {
        return evaluate(result, Map.of());
    }

    public static BusinessIntentEvaluation evaluate(BusinessIntentResult result,
            Map<String, Double> thresholdOverrides) {
        if (result == null || result.getIntents() == null || result.getIntents().isEmpty()) {
            return new BusinessIntentEvaluation(List.of(), BusinessIntentAction.UNKNOWN, List.of(),
                    "抱歉，我没能确定您的问题，请再描述一下具体需求。", "no-intent");
        }

        List<BusinessIntentMatch> matches = new ArrayList<>(result.getIntents());
        matches.removeIf(match -> match == null || !StringUtils.hasText(match.getType()));
        if (matches.isEmpty() || matches.stream()
                .allMatch(match -> BusinessIntentType.UNKNOWN.name().equalsIgnoreCase(match.getType()))) {
            return new BusinessIntentEvaluation(List.of(), BusinessIntentAction.UNKNOWN, List.of(),
                    "抱歉，我没能确定您的问题，请再描述一下具体需求。", "unknown-intent");
        }

        // 置信度是模型/规则给出的不可信输入，必须按意图风险做阈值过滤。
        List<BusinessIntentMatch> accepted = matches.stream()
                .filter(match -> match.getConfidence() != null
                        && match.getConfidence() >= confidenceThreshold(match.getType(), thresholdOverrides))
                .toList();
        if (accepted.isEmpty()) {
            return new BusinessIntentEvaluation(List.of(), BusinessIntentAction.UNKNOWN, List.of(),
                    "抱歉，我没能确定您的问题，请再描述一下具体需求。", "intent-below-confidence-threshold");
        }
        matches = new ArrayList<>(accepted);

        matches.sort(Comparator.comparingInt((BusinessIntentMatch match) -> priority(match.getType())).reversed());
        boolean transfer = matches.stream().anyMatch(match -> {
            BusinessIntentType type = BusinessIntentType.valueOf(match.getType());
            return type == BusinessIntentType.COMPLAINT || type == BusinessIntentType.HUMAN_TRANSFER
                    || type == BusinessIntentType.FEATURE_SUGGESTION;
        });
        if (transfer) {
            boolean complaintReasonMissing = matches.stream()
                    .filter(match -> BusinessIntentType.COMPLAINT.name().equals(match.getType()))
                    .anyMatch(match -> {
                        String reason = toSlotMap(match.getSlots()).get("reason");
                        return !StringUtils.hasText(reason) || reason.trim().length() < 6;
                    });
            if (complaintReasonMissing) {
                return new BusinessIntentEvaluation(matches, BusinessIntentAction.CLARIFY,
                        List.of("reason"), clarificationFor(List.of("reason")), "missing-complaint-reason");
            }
            return new BusinessIntentEvaluation(matches, BusinessIntentAction.TRANSFER_HUMAN, List.of(), null,
                    "high-risk-or-human-requested");
        }

        List<String> missing = missingSlots(matches);
        if (!missing.isEmpty()) {
            return new BusinessIntentEvaluation(matches, BusinessIntentAction.CLARIFY, missing,
                    clarificationFor(missing), "missing-slots");
        }

        boolean tool = matches.stream().anyMatch(match -> {
            BusinessIntentType type = BusinessIntentType.valueOf(match.getType());
            return type == BusinessIntentType.ORDER_QUERY || type == BusinessIntentType.ORDER_CREATE
                    || type == BusinessIntentType.LOGISTICS_QUERY;
        });
        if (tool) {
            return new BusinessIntentEvaluation(matches, BusinessIntentAction.TOOL, List.of(), null,
                    "business-tool-required");
        }

        boolean rag = matches.stream().anyMatch(match -> definitionFor(match.getType()).needsKnowledge());
        if (rag) {
            return new BusinessIntentEvaluation(matches, BusinessIntentAction.KNOWLEDGE_RAG, List.of(), null,
                    "knowledge-required");
        }
        if (matches.stream().anyMatch(match -> BusinessIntentType.CASUAL.name().equals(match.getType()))) {
            return new BusinessIntentEvaluation(matches, BusinessIntentAction.DIRECT_LLM, List.of(), null,
                    "casual-chat");
        }
        return new BusinessIntentEvaluation(matches, BusinessIntentAction.UNKNOWN, List.of(), null,
                "no-action-mapped");
    }

    public static BusinessIntentDefinition definitionFor(String type) {
        return BY_TYPE.get(normalizeTypeForLookup(type));
    }

    public static Set<String> configuredTypes(List<String> configuredNames) {
        if (configuredNames == null) {
            return Set.of();
        }
        Set<String> configured = configuredNames.stream()
                .map(BusinessIntentRegistry::normalizeType)
                .filter(StringUtils::hasText)
                .filter(BY_TYPE::containsKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> mandatory = Set.of(
                BusinessIntentType.CASUAL.name(),
                BusinessIntentType.HUMAN_TRANSFER.name(),
                BusinessIntentType.UNKNOWN.name());
        return java.util.stream.Stream.concat(configured.stream(), mandatory.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static BusinessIntentResult filterEnabledIntents(BusinessIntentResult result,
            Set<String> enabledTypes) {
        if (result == null || result.getIntents() == null || result.getIntents().isEmpty()
                || enabledTypes == null) {
            return result;
        }
        List<BusinessIntentMatch> filtered = result.getIntents().stream()
                .filter(match -> match != null && StringUtils.hasText(match.getType()))
                .filter(match -> enabledTypes.contains(normalizeType(match.getType()))
                        || BusinessIntentType.UNKNOWN.name().equalsIgnoreCase(match.getType()))
                .toList();
        if (filtered.isEmpty()) {
            BusinessIntentMatch unknown = new BusinessIntentMatch();
            unknown.setType(BusinessIntentType.UNKNOWN.name());
            unknown.setConfidence(0.3);
            unknown.setSlots(List.of());
            filtered = List.of(unknown);
        }
        return createResult(new ArrayList<>(filtered), "enabled-intents-filter");
    }

    public static String buildClassifierPrompt(String history, String format) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是客服业务意图识别器。你可以返回多个意图，因为用户一句话可能包含多个诉求。
                只返回结构化结果，不要生成客服回答。

                规则：
                1. 识别用户明确表达的业务意图，不要强行把所有问题归为一个意图。
                2. 一句话包含多个动作时返回多个意图，例如“订单没发货，我要投诉”同时返回 ORDER_QUERY、LOGISTICS_QUERY、COMPLAINT。
                3. 主意图取风险最高或最需要立即处理的意图。
                4. 这一步只做意图分类，不要提取或猜测业务参数。
                5. 无法确定业务意图时返回 UNKNOWN，不要编造意图。
                6. 输入中形如 [ORDER_A]、[PHONE_A] 的占位符代表受保护标识，保持原样，不得改写或猜测真实值。

                可用意图：
                """);
        for (BusinessIntentDefinition definition : DEFINITIONS) {
            builder.append("- ").append(definition.type().name())
                    .append("：").append(definition.description()).append("\n");
        }
        builder.append("\n历史对话：\n")
                .append(StringUtils.hasText(history) ? history.trim() : "（无）")
                .append("\n\n输出格式：\n")
                .append(format);
        return builder.toString();
    }

    public static String buildSlotExtractionPrompt(BusinessIntentDefinition definition, String history,
            String format) {
        if (definition == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("你是客服参数抽取器。只抽取 ")
                .append(definition.type().name())
                .append(" 意图所需的参数，不判断其他意图，不生成客服回答。\n")
                .append("意图说明：").append(definition.description()).append("\n\n")
                .append("只允许以下参数：\n");
        for (BusinessSlotDefinition slot : definition.slots()) {
            builder.append("- ").append(slot.name()).append("：").append(slot.description())
                    .append(slot.required() ? "（必填）" : "（可选）").append("\n");
        }
        builder.append("\n规则：\n")
                .append("1. 参数值必须来自用户输入或历史对话，不能猜测。\n")
                .append("2. 没有出现的参数不要填入。\n")
                .append("3. 不要把其他意图的参数放进结果。\n")
                .append("4. 形如 [ORDER_A]、[PHONE_A] 的占位符是受保护标识，必须原样填入对应的参数。\n\n")
                .append("历史对话：\n")
                .append(StringUtils.hasText(history) ? history.trim() : "（无）")
                .append("\n\n输出格式：\n")
                .append(format);
        return builder.toString();
    }

    public static List<BusinessSlot> sanitizeSlotResult(BusinessSlotResult raw, BusinessIntentType expected) {
        if (raw == null || expected == null || (StringUtils.hasText(raw.getIntent())
                && !expected.name().equalsIgnoreCase(normalizeType(raw.getIntent())))) {
            return null;
        }
        BusinessIntentDefinition definition = BY_TYPE.get(expected.name());
        if (definition == null || definition.slots().isEmpty()) {
            return List.of();
        }
        List<String> allowed = definition.slots().stream().map(BusinessSlotDefinition::name).toList();
        return normalizeSlots(raw.getSlots()).stream()
                .filter(slot -> allowed.contains(slot.getName()))
                .toList();
    }

    private static List<BusinessSlot> extractRuleSlots(String query, BusinessIntentType type) {
        Map<String, String> slots = new LinkedHashMap<>();
        Matcher orderMatcher = ORDER_NO_PATTERN.matcher(query);
        if (orderMatcher.find()) {
            slots.put("orderNo", orderMatcher.group());
        }
        Matcher phoneMatcher = PHONE_PATTERN.matcher(query);
        if (phoneMatcher.find()) {
            slots.put("phone", phoneMatcher.group());
        }
        if (type == BusinessIntentType.ORDER_CREATE) {
            Matcher productMatcher = ORDER_CREATE_PRODUCT_PATTERN.matcher(query);
            if (productMatcher.find()) {
                slots.put("productName", productMatcher.group(1).trim());
            }
            Matcher quantityMatcher = ORDER_CREATE_QUANTITY_PATTERN.matcher(query);
            if (quantityMatcher.find()) {
                slots.put("quantity", quantityMatcher.group(1));
            }
            Matcher addressMatcher = ORDER_CREATE_ADDRESS_PATTERN.matcher(query);
            if (addressMatcher.find()) {
                slots.put("address", addressMatcher.group(1).trim());
            }
        }
        if (type == BusinessIntentType.COMPLAINT && StringUtils.hasText(query)) {
            slots.put("reason", query);
        }
        return slots.entrySet().stream()
                .map(entry -> new BusinessSlot(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<String> missingSlots(List<BusinessIntentMatch> matches) {
        List<String> missing = new ArrayList<>();
        for (BusinessIntentMatch match : matches) {
            String type = match.getType();
            Map<String, String> slots = toSlotMap(match.getSlots());
            if (BusinessIntentType.ORDER_QUERY.name().equals(type)
                    || BusinessIntentType.LOGISTICS_QUERY.name().equals(type)) {
                if (!StringUtils.hasText(slots.get("orderNo")) && !StringUtils.hasText(slots.get("phone"))
                        && !StringUtils.hasText(slots.get("trackingNo"))) {
                    missing.add("orderNo|phone");
                }
            }
            if (BusinessIntentType.COMPLAINT.name().equals(type)) {
                String reason = slots.get("reason");
                if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
                    missing.add("reason");
                }
            }
            if (BusinessIntentType.ORDER_CREATE.name().equals(type)) {
                if (!StringUtils.hasText(slots.get("productName"))) {
                    missing.add("productName");
                }
                if (!StringUtils.hasText(slots.get("quantity"))) {
                    missing.add("quantity");
                }
                if (!StringUtils.hasText(slots.get("phone"))) {
                    missing.add("phone");
                }
                if (!StringUtils.hasText(slots.get("address"))) {
                    missing.add("address");
                }
            }
        }
        return missing.stream().distinct().toList();
    }

    private static String clarificationFor(List<String> missing) {
        if (missing.contains("orderNo|phone")) {
            return "请提供订单号或下单手机号，我才能继续为您查询订单。";
        }
        if (missing.contains("reason")) {
            return "请描述一下具体情况，例如订单号、遇到的问题和您希望如何处理。";
        }
        if (missing.stream().anyMatch(slot -> List.of("productName", "quantity", "phone", "address")
                .contains(slot))) {
            return "请补充商品名称、数量、联系电话和收货地址，我再为您创建订单。";
        }
        return "请补充更具体的信息，以便我继续处理。";
    }

    private static Map<String, String> toSlotMap(List<BusinessSlot> slots) {
        Map<String, String> result = new LinkedHashMap<>();
        if (slots != null) {
            for (BusinessSlot slot : slots) {
                if (slot != null && StringUtils.hasText(slot.getName())) {
                    result.put(slot.getName().trim(), slot.getValue() != null ? slot.getValue().trim() : null);
                }
            }
        }
        return result;
    }

    private static List<BusinessSlot> normalizeSlots(List<BusinessSlot> slots) {
        if (slots == null) {
            return List.of();
        }
        List<BusinessSlot> normalized = new ArrayList<>();
        for (BusinessSlot slot : slots) {
            if (slot == null || !StringUtils.hasText(slot.getName())) {
                continue;
            }
            String name = normalizeSlotName(slot.getName());
            if (StringUtils.hasText(name)) {
                normalized.add(new BusinessSlot(name,
                        slot.getValue() != null ? slot.getValue().trim() : null));
            }
        }
        return normalized;
    }

    private static List<BusinessSlot> mergeSlots(List<BusinessSlot> primary, List<BusinessSlot> fallback) {
        Map<String, BusinessSlot> merged = new LinkedHashMap<>();
        for (BusinessSlot slot : normalizeSlots(primary)) {
            if (StringUtils.hasText(slot.getValue())) {
                merged.put(slot.getName(), slot);
            }
        }
        for (BusinessSlot slot : normalizeSlots(fallback)) {
            if (StringUtils.hasText(slot.getValue())) {
                merged.putIfAbsent(slot.getName(), slot);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static BusinessIntentMatch copyMatch(BusinessIntentMatch source) {
        BusinessIntentMatch copy = new BusinessIntentMatch();
        copy.setType(normalizeType(source.getType()));
        copy.setConfidence(normalizeConfidence(source.getConfidence()));
        copy.setSlots(new ArrayList<>(normalizeSlots(source.getSlots())));
        return copy;
    }

    private static String normalizeSlotName(String name) {
        String normalized = name.trim();
        return switch (normalized) {
            case "order_no", "orderId", "order_id", "orderNumber", "order_number" -> "orderNo";
            case "phoneNumber", "phone_number", "mobile", "mobilePhone" -> "phone";
            case "tracking_number", "trackingNo", "trackingId", "tracking_id" -> "trackingNo";
            case "product", "product_name", "productName" -> "productName";
            default -> normalized;
        };
    }

    private static String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "CHITCHAT", "SMALL_TALK", "GREETING", "闲聊" -> BusinessIntentType.CASUAL.name();
            case "产品咨询", "产品咨询意图", "PRODUCT_INQUIRY" -> BusinessIntentType.PRODUCT_CONSULT.name();
            case "价格查询", "价格咨询", "PRICE_INQUIRY" -> BusinessIntentType.PRICE_QUERY.name();
            case "订单", "订单查询" -> BusinessIntentType.ORDER_QUERY.name();
            case "创建订单", "新建订单", "下单", "购买商品", "ORDER_CREATE" ->
                BusinessIntentType.ORDER_CREATE.name();
            case "物流", "物流查询" -> BusinessIntentType.LOGISTICS_QUERY.name();
            case "投诉", "投诉反馈" -> BusinessIntentType.COMPLAINT.name();
            case "退款", "退换货", "退款查询" -> BusinessIntentType.REFUND_QUERY.name();
            case "售后服务", "售后咨询" -> BusinessIntentType.AFTER_SALES.name();
            case "退换货申请", "退款退货" -> BusinessIntentType.REFUND_QUERY.name();
            case "支付问题", "付款问题" -> BusinessIntentType.PAYMENT_ISSUE.name();
            case "账号问题", "账户问题" -> BusinessIntentType.ACCOUNT_ISSUE.name();
            case "技术支持" -> BusinessIntentType.TECHNICAL_SUPPORT.name();
            case "功能建议", "需求反馈" -> BusinessIntentType.FEATURE_SUGGESTION.name();
            case "优惠活动", "促销咨询" -> BusinessIntentType.PROMOTION.name();
            case "人工", "转人工" -> BusinessIntentType.HUMAN_TRANSFER.name();
            case "其他", "未知" -> BusinessIntentType.UNKNOWN.name();
            default -> normalized;
        };
    }

    private static String normalizeTypeForLookup(String type) {
        String normalized = normalizeType(type);
        return normalized != null ? normalized : BusinessIntentType.UNKNOWN.name();
    }

    private static double normalizeConfidence(Double confidence) {
        if (confidence == null || Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0.5;
        }
        double value = confidence;
        if (value > 1.0 && value <= 100.0) {
            value /= 100.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static BusinessIntentResult createResult(List<BusinessIntentMatch> matches, String reason) {
        matches.sort(Comparator.comparingInt((BusinessIntentMatch match) -> priority(match.getType())).reversed());
        List<BusinessIntentMatch> limited = matches.stream().limit(6).toList();
        BusinessIntentResult result = new BusinessIntentResult();
        result.setIntents(new ArrayList<>(limited));
        result.setPrimaryIntent(limited.get(0).getType());
        result.setReason(reason);
        return result;
    }

    private static int priority(String type) {
        return switch (type) {
            case "HUMAN_TRANSFER" -> 100;
            case "ORDER_CREATE" -> 95;
            case "COMPLAINT" -> 90;
            case "REFUND_QUERY" -> 80;
            case "ORDER_QUERY" -> 70;
            case "LOGISTICS_QUERY" -> 65;
            case "PAYMENT_ISSUE" -> 60;
            case "AFTER_SALES" -> 50;
            case "ACCOUNT_ISSUE", "TECHNICAL_SUPPORT" -> 45;
            case "PRICE_QUERY", "PRODUCT_CONSULT" -> 40;
            case "PROMOTION", "FEATURE_SUGGESTION" -> 30;
            case "CASUAL" -> 10;
            default -> 0;
        };
    }

    private static double confidenceThreshold(String type, Map<String, Double> overrides) {
        if (overrides != null) {
            Double override = overrides.get(type);
            if (override == null) {
                override = overrides.entrySet().stream()
                        .filter(entry -> entry.getKey() != null && type.equalsIgnoreCase(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
            if (override != null && override >= 0 && override <= 1) {
                return override;
            }
        }
        return switch (type) {
            case "HUMAN_TRANSFER", "CASUAL" -> 0.80;
            case "ORDER_CREATE" -> 0.75;
            case "COMPLAINT" -> 0.75;
            case "ORDER_QUERY", "LOGISTICS_QUERY" -> 0.65;
            case "REFUND_QUERY", "PAYMENT_ISSUE", "AFTER_SALES" -> 0.60;
            case "ACCOUNT_ISSUE", "TECHNICAL_SUPPORT", "PRODUCT_CONSULT", "PRICE_QUERY" -> 0.55;
            default -> 0.55;
        };
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, BusinessIntentDefinition> buildTypeMap() {
        Map<String, BusinessIntentDefinition> map = new LinkedHashMap<>();
        for (BusinessIntentDefinition definition : DEFINITIONS) {
            map.put(definition.type().name(), definition);
        }
        map.put(BusinessIntentType.UNKNOWN.name(),
                new BusinessIntentDefinition(BusinessIntentType.UNKNOWN, "无法识别的业务意图",
                        List.of(), List.of(), false, BusinessIntentAction.UNKNOWN));
        return Map.copyOf(map);
    }
}
