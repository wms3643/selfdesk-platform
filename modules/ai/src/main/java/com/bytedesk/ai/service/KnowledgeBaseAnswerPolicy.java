package com.bytedesk.ai.service;

import org.springframework.util.StringUtils;

import com.bytedesk.ai.robot.RobotProtobuf;

/**
 * Central decision point for whether a robot may answer without knowledge-base evidence.
 *
 * <p>Customer-service robots should be evidence-first: the language model may organize an
 * answer, but it must not invent an answer when retrieval is unavailable or returns nothing.
 * Robots that intentionally provide open-ended chat can opt in through
 * {@code llm.useLlmWhenKbEmpty=true}.</p>
 */
public final class KnowledgeBaseAnswerPolicy {

    private KnowledgeBaseAnswerPolicy() {
    }

    public enum Route {
        DIRECT_LLM,
        KNOWLEDGE_BASE_AUGMENTED_LLM,
        KNOWLEDGE_BASE_ONLY,
        FALLBACK
    }

    public static boolean isKnowledgeBaseEnabled(RobotProtobuf robot) {
        return robot != null
                && Boolean.TRUE.equals(robot.getKbEnabled())
                && StringUtils.hasText(robot.getKbUid());
    }

    public static boolean isLlmEnabled(RobotProtobuf robot) {
        return robot != null
                && robot.getLlm() != null
                && Boolean.TRUE.equals(robot.getLlm().getEnabled());
    }

    /**
     * The flag defaults to false. A null value from historical rows is therefore treated as
     * strict retrieval mode rather than silently allowing an ungrounded answer.
     */
    public static boolean mayAnswerWithoutRetrieval(RobotProtobuf robot) {
        return robot != null
                && robot.getLlm() != null
                && Boolean.TRUE.equals(robot.getLlm().getUseLlmWhenKbEmpty());
    }

    public static Route resolve(RobotProtobuf robot, KnowledgeIntentRouter.Intent intent,
            boolean hasKnowledgeResults) {
        if (intent == KnowledgeIntentRouter.Intent.CASUAL) {
            return isLlmEnabled(robot) ? Route.DIRECT_LLM : Route.FALLBACK;
        }

        boolean knowledgeBaseEnabled = isKnowledgeBaseEnabled(robot);
        boolean llmEnabled = isLlmEnabled(robot);
        boolean allowWithoutRetrieval = mayAnswerWithoutRetrieval(robot);

        if (!knowledgeBaseEnabled) {
            return llmEnabled && allowWithoutRetrieval ? Route.DIRECT_LLM : Route.FALLBACK;
        }
        if (!llmEnabled) {
            return hasKnowledgeResults ? Route.KNOWLEDGE_BASE_ONLY : Route.FALLBACK;
        }
        if (hasKnowledgeResults) {
            return Route.KNOWLEDGE_BASE_AUGMENTED_LLM;
        }
        return allowWithoutRetrieval ? Route.DIRECT_LLM : Route.FALLBACK;
    }

    /**
     * Backward-compatible entry point for callers that have already decided the
     * message is a knowledge request.
     */
    public static Route resolve(RobotProtobuf robot, boolean hasKnowledgeResults) {
        return resolve(robot, KnowledgeIntentRouter.Intent.KNOWLEDGE, hasKnowledgeResults);
    }
}
