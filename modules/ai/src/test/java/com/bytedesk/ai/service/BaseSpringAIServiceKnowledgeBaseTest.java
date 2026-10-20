package com.bytedesk.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.bytedesk.ai.kbase.KbaseSearchHelper;
import com.bytedesk.ai.robot.RobotLlm;
import com.bytedesk.ai.robot.RobotProtobuf;
import com.bytedesk.core.constant.I18Consts;
import com.bytedesk.core.message.IMessageSendService;
import com.bytedesk.core.message.MessageProtobuf;
import com.bytedesk.core.message.content.RobotContent;
import com.bytedesk.core.thread.ThreadProtobuf;
import com.bytedesk.core.thread.event.RobotUnansweredEvent;
import com.bytedesk.kbase.llm_faq.FaqProtobuf;

class BaseSpringAIServiceKnowledgeBaseTest {

    private TestService service;
    private KbaseSearchHelper kbaseSearchHelper;
    private PromptHelper promptHelper;
    private MessagePersistenceHelper messagePersistenceHelper;
    private IMessageSendService messageSendService;
    private ApplicationEventPublisher eventPublisher;
    private RobotProtobuf robot;
    private MessageProtobuf queryMessage;
    private MessageProtobuf replyMessage;

    @BeforeEach
    void setUp() {
        service = new TestService();
        kbaseSearchHelper = mock(KbaseSearchHelper.class);
        promptHelper = mock(PromptHelper.class);
        messagePersistenceHelper = mock(MessagePersistenceHelper.class);
        messageSendService = mock(IMessageSendService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service.kbaseSearchHelper = kbaseSearchHelper;
        service.promptHelper = promptHelper;
        service.messagePersistenceHelper = messagePersistenceHelper;
        service.messageSendService = messageSendService;
        service.applicationEventPublisher = eventPublisher;

        robot = RobotProtobuf.builder()
                .uid("robot-1")
                .kbUid("kb-1")
                .kbEnabled(true)
                .kbSourceEnabled(true)
                .llm(RobotLlm.builder()
                        .enabled(true)
                        .useLlmWhenKbEmpty(false)
                        .build())
                .build();
        queryMessage = MessageProtobuf.builder()
                .uid("visitor-message-1")
                .content("退款多久到账")
                .thread(ThreadProtobuf.builder().uid("thread-1").topic("support/user-1").build())
                .build();
        replyMessage = MessageProtobuf.builder().uid("robot-message-1").build();
    }

    @Test
    void strictModeBlocksLlmWhenKnowledgeBaseIsNotConfigured() {
        robot.setKbUid(null);

        String answer = service.sendSyncMessage("退款多久到账", robot, queryMessage, replyMessage);

        assertEquals(I18Consts.I18N_ROBOT_DEFAULT_REPLY, answer);
        assertFalse(service.directLlmInvoked);
        verify(messagePersistenceHelper).persistMessage(eq(queryMessage), eq(replyMessage), eq(true),
                anyLong(), eq(0L), eq(0L), eq(0L), isNull(), eq(""), eq(""));
        verify(eventPublisher).publishEvent(any(RobotUnansweredEvent.class));
    }

    @Test
    void casualChatBypassesKnowledgeBaseAndUsesLlmDirectly() {
        robot.setKbUid(null);
        queryMessage.setContent("你好，谢谢你");
        when(promptHelper.buildMessagesForSync(eq("你好，谢谢你"), anyString(), same(robot), same(queryMessage)))
                .thenReturn(List.of(new UserMessage("你好，谢谢你")));
        when(promptHelper.toPrompt(any())).thenReturn(new Prompt("你好，谢谢你"));
        when(promptHelper.flattenPromptText(any())).thenReturn("你好，谢谢你");

        String answer = service.sendSyncMessage("你好，谢谢你", robot, queryMessage, replyMessage);

        assertEquals("有知识库依据的回答", answer);
        assertTrue(service.directLlmInvoked);
        verify(kbaseSearchHelper, never()).searchKnowledgeBaseWithSources(anyString(), any());
        verify(eventPublisher, never()).publishEvent(any(RobotUnansweredEvent.class));
    }

    @Test
    void strictModeFallsBackAndRequestsHumanWhenRetrievalIsEmpty() {
        when(kbaseSearchHelper.searchKnowledgeBaseWithSources("退款多久到账", robot))
                .thenReturn(new SearchResultWithSources(List.of(), List.of()));

        String answer = service.sendSyncMessage("退款多久到账", robot, queryMessage, replyMessage);

        assertEquals(I18Consts.I18N_ROBOT_DEFAULT_REPLY, answer);
        assertFalse(service.directLlmInvoked);

        ArgumentCaptor<RobotUnansweredEvent> eventCaptor = ArgumentCaptor.forClass(RobotUnansweredEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals("thread-1", eventCaptor.getValue().getThreadUid());
        assertEquals("knowledge-base-empty", eventCaptor.getValue().getReason());
    }

    @Test
    void syncAnswerCarriesOnlyRetrievedSourceReferences() {
        FaqProtobuf faq = FaqProtobuf.builder()
                .uid("faq-1")
                .question("退款多久到账")
                .answer("原路退回通常需要 1-3 个工作日")
                .type("FAQ")
                .build();
        RobotContent.SourceReference source = RobotContent.SourceReference.builder()
                .sourceUid("faq-1")
                .sourceName("退款说明")
                .contentSummary("原路退回通常需要 1-3 个工作日")
                .build();
        when(kbaseSearchHelper.searchKnowledgeBaseWithSources("退款多久到账", robot))
                .thenReturn(new SearchResultWithSources(List.of(faq), List.of(source)));
        when(promptHelper.buildMessagesForSync(eq("退款多久到账"), anyString(), same(robot), same(queryMessage)))
                .thenReturn(List.of(new UserMessage("退款多久到账")));
        when(promptHelper.toPrompt(any())).thenReturn(new Prompt("退款多久到账"));
        when(promptHelper.flattenPromptText(any())).thenReturn("退款多久到账");
        service.directLlmInvoked = false;

        String answer = service.sendSyncMessage("退款多久到账", robot, queryMessage, replyMessage);

        assertEquals("有知识库依据的回答", answer);
        assertTrue(service.directLlmInvoked);

        RobotContent content = RobotContent.fromJson(replyMessage.getContent(), RobotContent.class);
        assertEquals("有知识库依据的回答", content.getAnswer());
        assertEquals(1, content.getSources().size());
        assertEquals("退款说明", content.getSources().get(0).getSourceName());
        verify(eventPublisher, never()).publishEvent(any(RobotUnansweredEvent.class));
    }

    @Test
    void sseStrictModeUsesFallbackAndDoesNotCallModel() {
        robot.setKbUid(null);
        SseMessageHelper sseMessageHelper = mock(SseMessageHelper.class);
        service.sseMessageHelper = sseMessageHelper;
        SseEmitter emitter = new SseEmitter();

        service.sendSseMessage("退款多久到账", robot, queryMessage, replyMessage, emitter);

        verify(sseMessageHelper).sendDefaultReplySse(eq("退款多久到账"), eq(I18Consts.I18N_ROBOT_DEFAULT_REPLY),
                same(robot), same(queryMessage), same(replyMessage), same(emitter));
        verify(sseMessageHelper, never()).processPromptSseWithContext(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), anyString());
        verify(eventPublisher).publishEvent(any(RobotUnansweredEvent.class));
    }

    @Test
    void openAiIntentOptionsUseStrictJsonSchema() {
        String schema = "{\"type\":\"object\"}";

        ChatOptions options = service.buildKnowledgeIntentOptions("openai", "gpt-4.1-mini", schema);

        OpenAiChatOptions openAiOptions = assertInstanceOf(OpenAiChatOptions.class, options);
        assertEquals(Boolean.TRUE, openAiOptions.getStrict());
        assertEquals(schema, openAiOptions.getOutputSchema());
    }

    @Test
    void missingOrderIdentifierProducesClarificationWithoutTransfer() {
        queryMessage.setContent("我想查订单");

        String answer = service.sendSyncMessage("我想查订单", robot, queryMessage, replyMessage);

        assertTrue(answer.contains("订单号"));
        verify(eventPublisher, never()).publishEvent(any(RobotUnansweredEvent.class));
    }

    @Test
    void complaintIntentTransfersToHuman() {
        queryMessage.setContent("订单A123456三天没发货，我要投诉");

        service.sendSyncMessage("订单A123456三天没发货，我要投诉", robot, queryMessage, replyMessage);

        ArgumentCaptor<RobotUnansweredEvent> eventCaptor = ArgumentCaptor.forClass(RobotUnansweredEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertTrue(eventCaptor.getValue().getReason().contains("COMPLAINT"));
    }

    private static class TestService extends BaseSpringAIService {

        private boolean directLlmInvoked;

        @Override
        protected void processPromptSse(Prompt prompt, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
                MessageProtobuf messageProtobufReply, List<RobotContent.SourceReference> sourceReferences,
                SseEmitter emitter) {
            directLlmInvoked = true;
        }

        @Override
        protected String processPromptSync(String message, RobotProtobuf robot) {
            directLlmInvoked = true;
            return "有知识库依据的回答";
        }
    }
}
