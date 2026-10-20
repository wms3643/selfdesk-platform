package com.bytedesk.ai.service;

import java.util.List;
import java.util.Locale;

import org.springframework.util.StringUtils;

/**
 * Decides whether a user message can be answered as casual conversation or needs
 * business knowledge before the answer model runs.
 *
 * <p>The rule layer is intentionally conservative: only clear greetings, thanks,
 * identity/capability questions and similar small talk are classified as CASUAL.
 * Business keywords and question-shaped messages go to KNOWLEDGE. Anything else is
 * AMBIGUOUS and defaults to retrieval unless an optional small intent model resolves it.</p>
 */
public final class KnowledgeIntentRouter {

    public enum Intent {
        CASUAL,
        KNOWLEDGE,
        AMBIGUOUS
    }

    public record Decision(Intent intent, double confidence, String reason, boolean modelAssisted) {
        public Decision {
            intent = intent != null ? intent : Intent.AMBIGUOUS;
            reason = reason != null ? reason : "";
        }
    }

    private static final List<String> BUSINESS_MARKERS = List.of(
            "产品", "商品", "服务", "功能", "套餐", "方案", "价格", "费用", "收费", "多少钱", "报价",
            "订单", "退款", "退货", "换货", "售后", "发货", "物流", "快递", "运费", "发票", "支付", "付款",
            "账号", "账户", "登录", "密码", "会员", "积分", "优惠", "活动", "合同", "投诉", "保修", "维修",
            "地址", "电话", "营业时间", "政策", "规则", "流程", "条件", "材料", "资质", "证书", "隐私",
            "安全", "安装", "下载", "升级", "版本", "兼容", "支持", "数据", "客服", "人工", "怎么用",
            "使用方法", "配置", "接入", "购买", "续费", "到期", "取消", "修改", "查询",
            "price", "pricing", "cost", "order", "refund", "return", "shipping", "invoice", "payment",
            "account", "login", "password", "subscription", "policy", "warranty", "support",
            "how much", "how to", "how do");

    private static final List<String> CASUAL_MARKERS = List.of(
            "你好", "您好", "嗨", "哈喽", "hello", "hi", "在吗", "早上好", "上午好", "中午好", "下午好",
            "晚上好", "晚安", "谢谢", "多谢", "感谢", "辛苦了", "再见", "拜拜", "goodbye", "bye",
            "你是谁", "你叫什么", "你能做什么", "你会什么", "你是机器人吗", "自我介绍", "讲个笑话",
            "聊聊天", "陪我聊", "随便聊", "今天心情", "你觉得呢", "哈哈", "收到", "明白了", "好的", "ok", "okay");

    private static final List<String> STRONG_CASUAL_MARKERS = List.of(
            "你是谁", "你叫什么", "你能做什么", "你会什么", "你是机器人吗", "自我介绍", "讲个笑话",
            "聊聊天", "陪我聊", "随便聊", "今天心情", "你觉得", "最近怎么样", "过得怎么样",
            "吃了吗", "在干嘛", "忙吗");

    private static final List<String> QUESTION_MARKERS = List.of(
            "?", "？", "怎么", "如何", "为什么", "什么", "是否", "能否", "能不能", "可以吗", "多久",
            "多少", "哪里", "哪个", "什么时候", "有没有", "是不是", "能否", "请问");

    private KnowledgeIntentRouter() {
    }

    public static Decision classifyByRules(String query) {
        if (!StringUtils.hasText(query)) {
            return new Decision(Intent.AMBIGUOUS, 0.0, "empty-query", false);
        }
        String normalized = query.toLowerCase(Locale.ROOT).trim();

        boolean hasBusinessMarker = containsAny(normalized, BUSINESS_MARKERS);
        boolean hasCasualMarker = containsAny(normalized, CASUAL_MARKERS);
        boolean hasStrongCasualMarker = containsAny(normalized, STRONG_CASUAL_MARKERS);
        boolean questionShaped = containsAny(normalized, QUESTION_MARKERS);

        // A greeting followed by a business question is still a knowledge request.
        if (hasBusinessMarker) {
            return new Decision(Intent.KNOWLEDGE, 0.95, "business-marker", false);
        }
        if (hasStrongCasualMarker) {
            return new Decision(Intent.CASUAL, 0.9, "strong-casual-marker", false);
        }
        if (hasCasualMarker && !questionShaped) {
            return new Decision(Intent.CASUAL, 0.9, "casual-marker", false);
        }
        if (questionShaped) {
            return new Decision(Intent.KNOWLEDGE, 0.75, "question-shaped", false);
        }
        return new Decision(Intent.AMBIGUOUS, 0.5, "no-strong-rule", false);
    }

    public static Decision defaultKnowledge(String reason) {
        return new Decision(Intent.KNOWLEDGE, 0.5,
                StringUtils.hasText(reason) ? reason : "ambiguous-defaults-to-retrieval", false);
    }

    public static Decision fromModel(String intent, Double confidence, String reason) {
        if (!StringUtils.hasText(intent)) {
            return defaultKnowledge("model-returned-empty-intent");
        }
        String normalized = normalizeModelIntent(intent);
        double safeConfidence = normalizeConfidence(confidence);
        String safeReason = normalizeReason(reason);
        if ("CASUAL".equals(normalized)) {
            return new Decision(Intent.CASUAL, safeConfidence,
                    safeReason != null ? safeReason : "model-casual", true);
        }
        if ("KNOWLEDGE".equals(normalized)) {
            return new Decision(Intent.KNOWLEDGE, safeConfidence,
                    safeReason != null ? safeReason : "model-knowledge", true);
        }
        return defaultKnowledge("model-returned-unknown-intent");
    }

    public static String buildClassifierPrompt(String history, String format) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是一个客服对话路由器，只判断用户这句话是否需要查询企业知识库。

                分类：
                - CASUAL：打招呼、感谢、告别、询问机器人身份/能力、简短寒暄、情绪陪伴等，不需要企业事实。
                - KNOWLEDGE：询问公司、产品、价格、订单、退款、物流、账户、政策、流程、使用方法等需要企业事实的问题。

                判断规则：
                1. 问候语后面如果包含业务问题，必须判为 KNOWLEDGE。
                2. 依赖“它、这个、那个、上面说的”等指代时，结合历史对话判断；仍不确定就判 KNOWLEDGE。
                3. 不能因为用户语气轻松就判 CASUAL；只要需要企业事实，就是 KNOWLEDGE。
                4. 无法确定时判 KNOWLEDGE，宁可先检索，也不要让模型编造。
                5. confidence 范围 0-1；reason 保持简短。

                历史对话：
                """);
        builder.append(StringUtils.hasText(history) ? history.trim() : "（无）");
        builder.append("\n\n请只返回结构化结果：\n");
        builder.append(format);
        return builder.toString();
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (StringUtils.hasText(marker) && text.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeModelIntent(String intent) {
        String normalized = intent.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CASUAL", "CHITCHAT", "SMALL_TALK", "SMALLTALK", "GREETING", "CHAT", "闲聊", "打招呼" ->
                "CASUAL";
            case "KNOWLEDGE", "KNOWLEDGE_QUERY", "KNOWLEDGEBASE", "FAQ", "BUSINESS", "RAG", "知识",
                    "知识问答", "业务", "检索" -> "KNOWLEDGE";
            default -> normalized;
        };
    }

    private static double normalizeConfidence(Double confidence) {
        if (confidence == null || Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0.5;
        }
        double normalized = confidence;
        // 兼容模型返回 0-100 的情况。
        if (normalized > 1.0 && normalized <= 100.0) {
            normalized = normalized / 100.0;
        }
        return Math.max(0.0, Math.min(1.0, normalized));
    }

    private static String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String normalized = reason.trim();
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }
}
