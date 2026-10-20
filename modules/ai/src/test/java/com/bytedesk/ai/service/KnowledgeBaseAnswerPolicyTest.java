package com.bytedesk.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.bytedesk.ai.robot.RobotLlm;
import com.bytedesk.ai.robot.RobotProtobuf;
import com.bytedesk.ai.service.KnowledgeBaseAnswerPolicy.Route;

class KnowledgeBaseAnswerPolicyTest {

    @Test
    void serviceRobotWithoutKnowledgeBaseFallsBackEvenWhenLlmIsEnabled() {
        RobotProtobuf robot = robot(false, null, RobotLlm.builder().enabled(true).build());

        assertEquals(Route.FALLBACK, KnowledgeBaseAnswerPolicy.resolve(robot, false));
    }

    @Test
    void nullHistoricalFlagIsTreatedAsStrictRetrievalMode() {
        RobotLlm llm = RobotLlm.builder().enabled(true).build();
        llm.setUseLlmWhenKbEmpty(null);
        RobotProtobuf robot = robot(true, "kb-1", llm);

        assertFalse(KnowledgeBaseAnswerPolicy.mayAnswerWithoutRetrieval(robot));
        assertEquals(Route.FALLBACK, KnowledgeBaseAnswerPolicy.resolve(robot, false));
    }

    @Test
    void newRobotLlmDefaultsToStrictRetrievalMode() {
        assertFalse(new RobotLlm().getUseLlmWhenKbEmpty());
    }

    @Test
    void emptyKbFallsBackUnlessOpenChatIsExplicitlyEnabled() {
        RobotLlm strictLlm = RobotLlm.builder().enabled(true).useLlmWhenKbEmpty(false).build();
        RobotProtobuf strictRobot = robot(true, "kb-1", strictLlm);
        assertEquals(Route.FALLBACK, KnowledgeBaseAnswerPolicy.resolve(strictRobot, false));

        RobotLlm openLlm = RobotLlm.builder().enabled(true).useLlmWhenKbEmpty(true).build();
        RobotProtobuf openRobot = robot(true, "kb-1", openLlm);
        assertEquals(Route.DIRECT_LLM, KnowledgeBaseAnswerPolicy.resolve(openRobot, false));
    }

    @Test
    void retrievedKnowledgeUsesGroundedLlmOrKbOnlyAnswer() {
        RobotLlm llm = RobotLlm.builder().enabled(true).useLlmWhenKbEmpty(false).build();
        RobotProtobuf llmRobot = robot(true, "kb-1", llm);
        assertEquals(Route.KNOWLEDGE_BASE_AUGMENTED_LLM,
                KnowledgeBaseAnswerPolicy.resolve(llmRobot, true));

        RobotLlm disabledLlm = RobotLlm.builder().enabled(false).build();
        RobotProtobuf kbOnlyRobot = robot(true, "kb-1", disabledLlm);
        assertEquals(Route.KNOWLEDGE_BASE_ONLY, KnowledgeBaseAnswerPolicy.resolve(kbOnlyRobot, true));
        assertEquals(Route.FALLBACK, KnowledgeBaseAnswerPolicy.resolve(kbOnlyRobot, false));
    }

    @Test
    void knowledgeBaseRequiresBothSwitchAndUid() {
        RobotLlm llm = RobotLlm.builder().enabled(true).useLlmWhenKbEmpty(false).build();
        RobotProtobuf missingUid = robot(true, " ", llm);

        assertEquals(Route.FALLBACK, KnowledgeBaseAnswerPolicy.resolve(missingUid, true));
    }

    @Test
    void explicitOpenChatCanRunWithoutKnowledgeBase() {
        RobotLlm llm = RobotLlm.builder().enabled(true).useLlmWhenKbEmpty(true).build();
        RobotProtobuf robot = robot(false, null, llm);

        assertEquals(Route.DIRECT_LLM, KnowledgeBaseAnswerPolicy.resolve(robot, false));
    }

    @Test
    void casualChatDoesNotRequireKnowledgeBaseEvenInStrictMode() {
        RobotLlm llm = RobotLlm.builder().enabled(true).useLlmWhenKbEmpty(false).build();
        RobotProtobuf robot = robot(false, null, llm);

        assertEquals(Route.DIRECT_LLM, KnowledgeBaseAnswerPolicy.resolve(
                robot, KnowledgeIntentRouter.Intent.CASUAL, false));
    }

    private RobotProtobuf robot(boolean kbEnabled, String kbUid, RobotLlm llm) {
        return RobotProtobuf.builder()
                .uid("robot-1")
                .kbEnabled(kbEnabled)
                .kbUid(kbUid)
                .llm(llm)
                .build();
    }
}
