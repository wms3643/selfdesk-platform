package com.bytedesk.ai.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.bytedesk.ai.robot.RobotProtobuf;
import com.bytedesk.ai.robot_tool.RobotToolIntentContext;
import com.bytedesk.ai.kbase.KbaseSearchHelper;
import com.bytedesk.ai.llm_provider.LlmProviderRestService;
import com.bytedesk.ai.provider.dashscope.tool.DashScopeToolService;
import com.bytedesk.ai.provider.zhipuai.tool.ZhipuaiToolService;
import com.bytedesk.ai.robot.RobotRestService;
import com.bytedesk.ai.robot_message.RobotMessageCache;
import com.bytedesk.ai.service.agent.AgentCannedResponseMatch;
import com.bytedesk.ai.service.agent.AgentCannedResponseRequest;
import com.bytedesk.ai.service.agent.AgentCannedResponseResolver;
import com.bytedesk.ai.service.agent.AgentCannedResponseTraceContext;
import com.bytedesk.ai.service.agent.AgentResponseTimingContext;
import com.bytedesk.ai.springai.adviser.AdvisorChainFactory;
import com.bytedesk.ai.springai.adviser.RagQueryRewriteHelper;
import com.bytedesk.ai.springai.config.ChatClientBuilderFactory;
import com.bytedesk.ai.springai.service.ChatClientInfoService;
import com.bytedesk.ai.tool.utils.RobotToolCallbackResolver;
import com.bytedesk.core.constant.I18Consts;
import com.bytedesk.core.config.metrics.BytedeskMetrics;
import com.bytedesk.core.llm.LlmProviderConstants;
import com.bytedesk.core.message.IMessageSendService;
import com.bytedesk.core.message.MessagePersistCache;
import com.bytedesk.core.message.MessageProtobuf;
import com.bytedesk.core.message.MessageRestService;
import com.bytedesk.core.message.content.RobotContent;
import com.bytedesk.core.message.enums.MessageTypeEnum;
import com.bytedesk.core.thread.ThreadRestService;
import com.bytedesk.core.thread.event.RobotUnansweredEvent;
import com.bytedesk.core.uid.UidUtils;
import com.bytedesk.kbase.llm_faq.FaqProtobuf;
import com.bytedesk.kbase.settings_intention.IntentionSettingsEntity;
import com.bytedesk.ai.robot.RobotLlm;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
public abstract class BaseSpringAIService implements SpringAIService {

    private static final ExecutorService INTENT_MODEL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    protected IMessageSendService messageSendService;

    protected UidUtils uidUtils;

    protected RobotRestService robotRestService;

    protected ThreadRestService threadRestService;

    protected MessagePersistCache messagePersistCache;

    protected RobotMessageCache robotMessageCache;

    protected MessageRestService messageRestService;

    protected ApplicationEventPublisher applicationEventPublisher;

    protected KbaseSearchHelper kbaseSearchHelper;

    protected PromptHelper promptHelper;

    protected MessagePersistenceHelper messagePersistenceHelper;

    protected SseMessageHelper sseMessageHelper;

    protected ChatClientBuilderFactory chatClientBuilderFactory;

    protected ChatClientInfoService chatClientInfoService;

    protected AgentCannedResponseResolver agentCannedResponseResolver;

    protected AgentCannedResponseTraceContext agentCannedResponseTraceContext;

    protected AgentResponseTimingContext agentResponseTimingContext;

    protected RobotToolCallbackResolver robotToolCallbackResolver;

    protected DashScopeToolService dashScopeToolService;

    protected ZhipuaiToolService zhipuaiToolService;

    protected LlmProviderRestService llmProviderRestService;

    protected ProviderToolServiceDispatcher providerToolServiceDispatcher;

    protected ReasoningContentHelper reasoningContentHelper;

    protected CannedResponseEvidenceHelper cannedResponseEvidenceHelper;

    // Spring AI Advisor 链组装工厂（阶段2/3 横切关注点）。
    // 集中管理 SafeGuard / ReReading / Logging / Memory，从基类抽取以精简实现。
    // 可选注入：工厂内部所有依赖均为 ObjectProvider 优雅降级，bean 不存在时 buildAdvisorChain 返回空 list。
    // 经 setBaseDependencies setter 注入（与其它 30+ 依赖一致），保持子类无参 super() 不受影响。
    protected AdvisorChainFactory advisorChainFactory;

    // RAG Query 增强助手（阶段4）。可选注入：开关关闭时行为零变化。
    // 仅在 robot.llm.ragRewriteEnabled/ragMultiQueryEnabled=true 时生效，KB 搜索流程本身不改动。
    protected RagQueryRewriteHelper ragQueryRewriteHelper;

    protected BusinessIntentProperties businessIntentProperties;

    protected BytedeskMetrics bytedeskMetrics;

    private final ThreadLocal<Map<String, Object>> providerToolRuntimeContextHolder = new ThreadLocal<>();

    // Sync 路径的 conversationId 载体（threadTopic，与 PromptHelper 手动历史同键）。
    // SSE 路径是异步流式（.subscribe() 跨线程），ThreadLocal 不可靠，故 SSE 用显式 invokePromptStream(.., conversationId)；
    // Sync 是同步同线程，用 ThreadLocal 在 sendSyncMessage 入口设置、2 参 invokePromptSync 读取，避免改动 30 个抽象方法签名。
    private final ThreadLocal<String> conversationIdHolder = new ThreadLocal<>();

    // 保留一个无参构造函数，或者只接收特定的必需依赖
    protected BaseSpringAIService() {
        // 无参构造函数
    }

    @Autowired
    protected void setBaseDependencies(
            IMessageSendService messageSendService,
            UidUtils uidUtils,
            RobotRestService robotRestService,
            ThreadRestService threadRestService,
            MessagePersistCache messagePersistCache,
            RobotMessageCache robotMessageCache,
            MessageRestService messageRestService,
            ApplicationEventPublisher applicationEventPublisher,
            KbaseSearchHelper knowledgeBaseSearchHelper,
            PromptHelper promptHelper,
            MessagePersistenceHelper messagePersistenceHelper,
            SseMessageHelper sseMessageHelper,
            ObjectProvider<ChatClientBuilderFactory> chatClientBuilderFactoryProvider,
            ObjectProvider<ChatClientInfoService> chatClientInfoServiceProvider,
            ObjectProvider<AgentCannedResponseResolver> agentCannedResponseResolverProvider,
            ObjectProvider<AgentCannedResponseTraceContext> agentCannedResponseTraceContextProvider,
            ObjectProvider<AgentResponseTimingContext> agentResponseTimingContextProvider,
            ObjectProvider<RobotToolCallbackResolver> robotToolCallbackResolverProvider,
            ObjectProvider<DashScopeToolService> dashScopeToolServiceProvider,
            ObjectProvider<ZhipuaiToolService> zhipuaiToolServiceProvider,
            ObjectProvider<LlmProviderRestService> llmProviderRestServiceProvider,
            ObjectProvider<ProviderToolServiceDispatcher> providerToolServiceDispatcherProvider,
            ObjectProvider<ReasoningContentHelper> reasoningContentHelperProvider,
            ObjectProvider<CannedResponseEvidenceHelper> cannedResponseEvidenceHelperProvider,
            ObjectProvider<AdvisorChainFactory> advisorChainFactoryProvider,
            ObjectProvider<RagQueryRewriteHelper> ragQueryRewriteHelperProvider,
            ObjectProvider<BusinessIntentProperties> businessIntentPropertiesProvider,
            ObjectProvider<BytedeskMetrics> bytedeskMetricsProvider) {
        this.messageSendService = messageSendService;
        this.uidUtils = uidUtils;
        this.robotRestService = robotRestService;
        this.threadRestService = threadRestService;
        this.messagePersistCache = messagePersistCache;
        this.robotMessageCache = robotMessageCache;
        this.messageRestService = messageRestService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.kbaseSearchHelper = knowledgeBaseSearchHelper;
        this.promptHelper = promptHelper;
        this.messagePersistenceHelper = messagePersistenceHelper;
        this.sseMessageHelper = sseMessageHelper;
        this.chatClientBuilderFactory = chatClientBuilderFactoryProvider.getIfAvailable();
        this.chatClientInfoService = chatClientInfoServiceProvider.getIfAvailable();
        this.agentCannedResponseResolver = agentCannedResponseResolverProvider.getIfAvailable();
        this.agentCannedResponseTraceContext = agentCannedResponseTraceContextProvider.getIfAvailable();
        this.agentResponseTimingContext = agentResponseTimingContextProvider.getIfAvailable();
        this.robotToolCallbackResolver = robotToolCallbackResolverProvider.getIfAvailable();
        this.dashScopeToolService = dashScopeToolServiceProvider.getIfAvailable();
        this.zhipuaiToolService = zhipuaiToolServiceProvider.getIfAvailable();
        this.llmProviderRestService = llmProviderRestServiceProvider.getIfAvailable();
        this.providerToolServiceDispatcher = providerToolServiceDispatcherProvider.getIfAvailable();
        this.reasoningContentHelper = reasoningContentHelperProvider.getIfAvailable();
        this.cannedResponseEvidenceHelper = cannedResponseEvidenceHelperProvider.getIfAvailable();
        this.advisorChainFactory = advisorChainFactoryProvider.getIfAvailable();
        this.ragQueryRewriteHelper = ragQueryRewriteHelperProvider.getIfAvailable();
        this.businessIntentProperties = businessIntentPropertiesProvider.getIfAvailable();
        this.bytedeskMetrics = bytedeskMetricsProvider.getIfAvailable();
    }

    protected String tryProcessPromptSyncWithProviderToolService(String message, RobotProtobuf robot) {
        if (providerToolServiceDispatcher == null) {
            return null;
        }
        return providerToolServiceDispatcher.tryDispatch(robot, message,
                providerToolRuntimeContextHolder.get(),
                this::processSyncForIntentRecognition);
    }

    protected boolean tryProcessPromptSseWithProviderToolService(Prompt prompt, RobotProtobuf robot,
            MessageProtobuf messageProtobufQuery, MessageProtobuf messageProtobufReply,
            List<RobotContent.SourceReference> sourceReferences, SseEmitter emitter) {
        if (providerToolServiceDispatcher == null) {
            return false;
        }
        String userMessage = messageProtobufQuery != null
                ? providerToolServiceDispatcher.extractLikelyUserMessage(messageProtobufQuery.getContent())
                : null;
        if (!StringUtils.hasText(userMessage)) {
            userMessage = flattenPromptText(prompt);
        }
        if (!StringUtils.hasText(userMessage)) {
            return false;
        }

        String answer = providerToolServiceDispatcher.tryDispatch(robot, userMessage,
                providerToolRuntimeContextHolder.get(),
                this::processSyncForIntentRecognition);
        if (!StringUtils.hasText(answer)) {
            return false;
        }

        String provider = providerToolServiceDispatcher.resolveToolExecutionProvider(robot);
        String model = providerToolServiceDispatcher.resolveToolExecutionModel(robot);
        sseMessageHelper.sendStreamStartMessage(messageProtobufQuery, messageProtobufReply, emitter,
                I18Consts.I18N_THINKING);
        sseMessageHelper.sendStreamMessage(messageProtobufQuery, messageProtobufReply, emitter, answer, null,
                sourceReferences);
        sseMessageHelper.sendStreamEndMessage(messageProtobufQuery, messageProtobufReply, emitter, 0, 0, 0, prompt,
                provider, model);
        return true;
    }

    /**
     * Adapter to pass the structured sync processor to external helpers.
     */
    private <T> T processSyncForIntentRecognition(String query, RobotProtobuf robot, String provider,
            String model, Class<T> outputClass) {
        return processSyncRequest(query, robot, provider, model, outputClass);
    }

    private String flattenPromptText(Prompt prompt) {
        return promptHelper != null ? promptHelper.flattenPromptText(prompt) : null;
    }

    @SuppressWarnings("unchecked")
    protected <T extends ToolCallingChatOptions> T applyRobotToolCallbacks(T options, RobotLlm llm) {
        if (options == null || llm == null || robotToolCallbackResolver == null) {
            return options;
        }

        List<ToolCallback> toolCallbacks = robotToolCallbackResolver.resolveToolCallbacks(
                llm.getTools(), llm.getMaxToolInvocations());
        if (toolCallbacks.isEmpty()) {
            return options;
        }

        ToolCallingChatOptions.Builder<?> builder = options.mutate();
        builder.toolCallbacks(toolCallbacks);
        if (StringUtils.hasText(llm.getTextProvider())) {
            builder.toolContext("provider", llm.getTextProvider());
        }
        if (StringUtils.hasText(llm.getTextModel())) {
            builder.toolContext("model", llm.getTextModel());
        }
        if (StringUtils.hasText(llm.getToolChoice())) {
            // 兼容保留：toolContext 供既有工具/审计读取
            builder.toolContext("toolChoice", llm.getToolChoice());
        }
        if (llm.getMaxToolInvocations() != null && llm.getMaxToolInvocations() > 0) {
            // 便于审计记录追溯本次请求的上限配置（实际拦截在 AuditedToolCallback 内）
            builder.toolContext("maxToolInvocations", llm.getMaxToolInvocations());
        }
        T built = (T) builder.build();
        applyProviderToolChoice(built, llm.getToolChoice());
        return built;
    }

    /**
     * 工具选择模式写入 provider 请求参数（规划 G8 修复）。
     *
     * <p>此前 toolChoice 只写入 toolContext（执行时传给工具的元数据），对模型请求无效；
     * 现补充写入各 provider ChatOptions 的 toolChoice 字段，由 ChatModel 透传至 API
     * （DashScope GenerationParam.toolChoice / zai toolChoice，取值 auto/none/required）。</p>
     *
     * <p>DeepSeekChatOptions（Spring AI 官方）暂无 toolChoice 字段，保持默认 auto 行为。</p>
     */
    private void applyProviderToolChoice(ToolCallingChatOptions options, String toolChoice) {
        if (options == null || !StringUtils.hasText(toolChoice)) {
            return;
        }
        String providerToolChoice = toProviderToolChoice(toolChoice);
        if (options instanceof com.bytedesk.ai.provider.dashscope.chat.DashScopeChatOptions dashScopeOptions) {
            dashScopeOptions.setToolChoice(providerToolChoice);
        } else if (options instanceof com.bytedesk.ai.provider.zhipuai.chat.ZhipuaiChatOptions zhipuaiOptions) {
            zhipuaiOptions.setToolChoice(providerToolChoice);
        }
    }

    /** AUTO/NONE/REQUIRED（ToolChoice 枚举名）→ provider 取值 auto/none/required。 */
    private String toProviderToolChoice(String toolChoice) {
        return switch (toolChoice.trim().toUpperCase()) {
            case "NONE" -> "none";
            case "REQUIRED" -> "required";
            default -> "auto";
        };
    }

    protected String extractReasoningContent(Generation generation, AssistantMessage assistantMessage) {
        if (reasoningContentHelper == null) {
            return null;
        }
        return reasoningContentHelper.extractReasoningContent(generation, assistantMessage);
    }

    @Override
    public void sendSseMessage(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, SseEmitter emitter) {
        Assert.hasText(query, "Query must not be empty");
        Assert.notNull(emitter, "SseEmitter must not be null");

        boolean kbEnabled = KnowledgeBaseAnswerPolicy.isKnowledgeBaseEnabled(robot);
        KnowledgeIntentRouter.Decision intentDecision;
        BusinessIntentEvaluation businessIntent = classifyBusinessIntent(query, robot, messageProtobufQuery);
        recordBusinessIntentMetrics(businessIntent);
        if (businessIntent.action() == BusinessIntentAction.DIRECT_LLM) {
            if (KnowledgeBaseAnswerPolicy.isLlmEnabled(robot)) {
                sseMessageHelper.processPromptSseWithContext(this, query, "", robot, messageProtobufQuery,
                        messageProtobufReply, new ArrayList<>(), emitter, "BUSINESS_CASUAL");
            } else {
                sendUnansweredSse(query, robot, messageProtobufQuery, messageProtobufReply, emitter,
                        "business-casual-but-llm-disabled");
            }
            return;
        }
        if (businessIntent.action() == BusinessIntentAction.CLARIFY) {
            recordBusinessClarification(businessIntent);
            sendClarificationSse(query, robot, messageProtobufQuery, messageProtobufReply, emitter,
                    businessIntent.clarificationQuestion());
            return;
        }
        if (businessIntent.action() == BusinessIntentAction.TRANSFER_HUMAN) {
            String transferReason = "business-intent-transfer:" + businessIntent.intentSummary();
            if (hasBusinessIntent(businessIntent, BusinessIntentType.COMPLAINT)) {
                BusinessIntentEvaluation complaintOnly = withOnlyIntent(businessIntent,
                        BusinessIntentType.COMPLAINT);
                long complaintStartedAt = System.nanoTime();
                BusinessToolResult complaintResult = BusinessToolExecutor.execute(robotRestService, robot, query,
                        complaintOnly, businessToolInvocation(messageProtobufQuery));
                recordBusinessTool(complaintOnly, complaintResult.success(), complaintStartedAt);
                transferReason += complaintResult.success()
                        ? ":complaint-created"
                        : ":" + complaintResult.error();
            }
            recordBusinessTransfer(businessIntent, transferReason);
            sendUnansweredSse(query, robot, messageProtobufQuery, messageProtobufReply, emitter, transferReason);
            return;
        }
        if (businessIntent.action() == BusinessIntentAction.TOOL) {
            long toolStartedAt = System.nanoTime();
            BusinessToolResult toolResult = BusinessToolExecutor.execute(robotRestService, robot, query,
                    businessIntent, businessToolInvocation(messageProtobufQuery));
            recordBusinessTool(businessIntent, toolResult.success(), toolStartedAt);
            if (!toolResult.success()) {
                // 工具未配置、HTTP 失败或业务系统拒绝时，转人工，不能编造查询结果。
                sendUnansweredSse(query, robot, messageProtobufQuery, messageProtobufReply, emitter,
                        toolResult.error());
                return;
            }
            if (KnowledgeBaseAnswerPolicy.isLlmEnabled(robot)) {
                sseMessageHelper.processPromptSseWithToolContext(this, query, toolResult.content(), robot,
                        messageProtobufQuery, messageProtobufReply, emitter);
            } else {
                sendUnansweredSse(query, robot, messageProtobufQuery, messageProtobufReply, emitter,
                        "business-tool-result-without-llm");
            }
            return;
        }
        if (businessIntent.action() == BusinessIntentAction.KNOWLEDGE_RAG) {
            intentDecision = new KnowledgeIntentRouter.Decision(
                    KnowledgeIntentRouter.Intent.KNOWLEDGE, 0.9,
                    "business-intent:" + businessIntent.intentSummary(), true);
        } else {
            intentDecision = classifyKnowledgeIntent(query, robot, messageProtobufQuery);
        }
        List<FaqProtobuf> kbResults = Collections.emptyList();
        List<RobotContent.SourceReference> sourceReferences = Collections.emptyList();

        if (intentDecision.intent() != KnowledgeIntentRouter.Intent.CASUAL && kbEnabled) {
            SearchResultWithSources aggregated = searchKnowledgeBaseWithSourcesRag(query, robot,
                    extractConversationId(messageProtobufQuery),
                    messageProtobufQuery != null ? messageProtobufQuery.getUid() : null);
            kbResults = aggregated.getSearchResults();
            sourceReferences = visibleSourceReferences(robot, aggregated);
        }

        KnowledgeBaseAnswerPolicy.Route route = KnowledgeBaseAnswerPolicy.resolve(
                robot, intentDecision.intent(), !kbResults.isEmpty());
        log.info("Answer route: intent={}, route={}, kbEnabled={}, kbResults={}, sources={}",
                intentDecision.intent(), route, kbEnabled, kbResults.size(), sourceReferences.size());

        switch (route) {
            case DIRECT_LLM -> sseMessageHelper.processPromptSseWithContext(this, query, "", robot,
                    messageProtobufQuery, messageProtobufReply, new ArrayList<>(), emitter, "DIRECT_LLM");
            case FALLBACK -> sendUnansweredSse(query, robot, messageProtobufQuery, messageProtobufReply,
                    emitter, resolveFallbackReason(intentDecision.intent(), kbEnabled, kbResults));
            case KNOWLEDGE_BASE_AUGMENTED_LLM -> {
                String context = promptHelper.buildContextFromFaqs(kbResults);
                sseMessageHelper.processPromptSseWithContext(this, query, context, robot, messageProtobufQuery,
                        messageProtobufReply, sourceReferences, emitter, "KB_AUGMENTED_LLM");
            }
            case KNOWLEDGE_BASE_ONLY -> sendKnowledgeBaseOnlySse(query, robot, messageProtobufQuery,
                    messageProtobufReply, emitter, kbResults, sourceReferences);
        }
    }

    @Override
    public String sendSyncMessage(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply) {
        Assert.hasText(query, "Query must not be empty");
        Assert.notNull(robot, "Robot must not be null");
        Assert.notNull(messageProtobufQuery, "MessageProtobufQuery must not be null");
        Assert.notNull(messageProtobufReply, "MessageProtobufReply must not be null");

        long startedAtNanos = System.nanoTime();

        // Sync 路径 conversationId（threadTopic）经 ThreadLocal 传给下游 invokePromptSync，使 Memory Advisor 生效
        String conversationId = extractConversationId(messageProtobufQuery);
        conversationIdHolder.set(conversationId);

        try {
            boolean kbEnabled = KnowledgeBaseAnswerPolicy.isKnowledgeBaseEnabled(robot);
            KnowledgeIntentRouter.Decision intentDecision;
            BusinessIntentEvaluation businessIntent = classifyBusinessIntent(query, robot, messageProtobufQuery);
            recordBusinessIntentMetrics(businessIntent);
            if (businessIntent.action() == BusinessIntentAction.DIRECT_LLM) {
                if (KnowledgeBaseAnswerPolicy.isLlmEnabled(robot)) {
                    return persistDirectLlmSync(query, robot, messageProtobufQuery, messageProtobufReply,
                            startedAtNanos);
                }
                return persistUnansweredSync(query, robot, messageProtobufQuery, messageProtobufReply,
                        startedAtNanos, "business-casual-but-llm-disabled");
            }
            if (businessIntent.action() == BusinessIntentAction.CLARIFY) {
                recordBusinessClarification(businessIntent);
                return persistClarificationSync(query, robot, messageProtobufQuery, messageProtobufReply,
                        startedAtNanos, businessIntent.clarificationQuestion());
            }
            if (businessIntent.action() == BusinessIntentAction.TRANSFER_HUMAN) {
                String transferReason = "business-intent-transfer:" + businessIntent.intentSummary();
                if (hasBusinessIntent(businessIntent, BusinessIntentType.COMPLAINT)) {
                    BusinessIntentEvaluation complaintOnly = withOnlyIntent(businessIntent,
                            BusinessIntentType.COMPLAINT);
                    long complaintStartedAt = System.nanoTime();
                    BusinessToolResult complaintResult = BusinessToolExecutor.execute(robotRestService, robot,
                            query, complaintOnly, businessToolInvocation(messageProtobufQuery));
                    recordBusinessTool(complaintOnly, complaintResult.success(), complaintStartedAt);
                    transferReason += complaintResult.success()
                            ? ":complaint-created"
                            : ":" + complaintResult.error();
                }
                recordBusinessTransfer(businessIntent, transferReason);
                return persistUnansweredSync(query, robot, messageProtobufQuery, messageProtobufReply,
                        startedAtNanos, transferReason);
            }
            if (businessIntent.action() == BusinessIntentAction.TOOL) {
                long toolStartedAt = System.nanoTime();
                BusinessToolResult toolResult = BusinessToolExecutor.execute(robotRestService, robot, query,
                        businessIntent, businessToolInvocation(messageProtobufQuery));
                recordBusinessTool(businessIntent, toolResult.success(), toolStartedAt);
                if (!toolResult.success()) {
                    return persistUnansweredSync(query, robot, messageProtobufQuery, messageProtobufReply,
                            startedAtNanos, toolResult.error());
                }
                if (!KnowledgeBaseAnswerPolicy.isLlmEnabled(robot)) {
                    return persistUnansweredSync(query, robot, messageProtobufQuery, messageProtobufReply,
                            startedAtNanos, "business-tool-result-without-llm");
                }
                return persistToolAugmentedSync(query, robot, messageProtobufQuery, messageProtobufReply,
                        startedAtNanos, toolResult.content());
            }
            if (businessIntent.action() == BusinessIntentAction.KNOWLEDGE_RAG) {
                intentDecision = new KnowledgeIntentRouter.Decision(
                        KnowledgeIntentRouter.Intent.KNOWLEDGE, 0.9,
                        "business-intent:" + businessIntent.intentSummary(), true);
            } else {
                intentDecision = classifyKnowledgeIntent(query, robot, messageProtobufQuery);
            }
            List<FaqProtobuf> kbResults = Collections.emptyList();
            List<RobotContent.SourceReference> sourceReferences = Collections.emptyList();

            if (intentDecision.intent() != KnowledgeIntentRouter.Intent.CASUAL && kbEnabled) {
                SearchResultWithSources aggregated = searchKnowledgeBaseWithSourcesRag(query, robot,
                        extractConversationId(messageProtobufQuery),
                        messageProtobufQuery != null ? messageProtobufQuery.getUid() : null);
                kbResults = aggregated.getSearchResults();
                sourceReferences = visibleSourceReferences(robot, aggregated);
            }

            KnowledgeBaseAnswerPolicy.Route route = KnowledgeBaseAnswerPolicy.resolve(
                    robot, intentDecision.intent(), !kbResults.isEmpty());
            log.info("sendSyncMessage intent={}, route={}, kbEnabled={}, kbResults={}, sources={}",
                    intentDecision.intent(), route, kbEnabled, kbResults.size(), sourceReferences.size());

            return switch (route) {
                case DIRECT_LLM -> persistDirectLlmSync(query, robot, messageProtobufQuery, messageProtobufReply,
                        startedAtNanos);
                case FALLBACK -> persistUnansweredSync(query, robot, messageProtobufQuery, messageProtobufReply,
                        startedAtNanos, resolveFallbackReason(intentDecision.intent(), kbEnabled, kbResults));
                case KNOWLEDGE_BASE_AUGMENTED_LLM -> persistKnowledgeBaseAugmentedSync(query, robot,
                        messageProtobufQuery, messageProtobufReply, startedAtNanos, kbResults, sourceReferences);
                case KNOWLEDGE_BASE_ONLY -> persistKnowledgeBaseOnlySync(query, robot, messageProtobufQuery,
                        messageProtobufReply, startedAtNanos, kbResults, sourceReferences);
            };
        } catch (Exception e) {
            log.error("Error in sendSyncMessage", e);
            String errorMessage = I18Consts.I18N_SERVICE_TEMPORARILY_UNAVAILABLE;
            RobotContent streamError = RobotContent.builder()
                    .question(query)
                    .answer("")
                    .reasonContent(errorMessage)
                    .kbUid(robot.getKbUid())
                    .robotUid(robot.getUid())
                    .build();
            messageProtobufReply.setContent(streamError.toJson());
            messageProtobufReply.setType(MessageTypeEnum.ROBOT_ERROR);
            messagePersistenceHelper.persistMessage(messageProtobufQuery, messageProtobufReply, true,
                    elapsedMillis(startedAtNanos), 0, 0, 0, null, "", "");
            messageSendService.sendProtobufMessage(messageProtobufReply);
            return errorMessage;
        } finally {
            // 清除 Sync 路径 conversationId ThreadLocal，避免线程池复用时串会话
            conversationIdHolder.remove();
        }
    }

    private String persistDirectLlmSync(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, long startedAtNanos) {
        List<Message> messages = promptHelper.buildMessagesForSync(query, "", robot, messageProtobufQuery);
        Prompt aiPrompt = promptHelper.toPrompt(messages);
        String response = withProviderToolRuntimeContext(robot, messageProtobufQuery,
                () -> processPromptSync(aiPrompt, robot));
        String modelType = robot.getLlm() != null && robot.getLlm().getTextModel() != null
                ? robot.getLlm().getTextModel()
                : "";
        return persistSyncRobotContent(query, robot, messageProtobufQuery, messageProtobufReply,
                response, MessageTypeEnum.ROBOT, false, null, Collections.emptyList(), aiPrompt, "", modelType,
                startedAtNanos, null);
    }

    private void sendKnowledgeBaseOnlySse(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, SseEmitter emitter, List<FaqProtobuf> kbResults,
            List<RobotContent.SourceReference> sourceReferences) {
        FaqProtobuf firstFaq = kbResults.get(0);
        if (kbResults.size() > 1) {
            firstFaq.setRelatedFaqs(new ArrayList<>(kbResults.subList(1, kbResults.size())));
        }
        String context = promptHelper.buildContextFromFaqs(kbResults);
        RobotContent.RobotContentBuilder<?, ?> contentBuilder = RobotContent.builder()
                .question(query)
                .answer(firstFaq.toJson())
                .kbUid(robot.getKbUid())
                .robotUid(robot.getUid());
        if (StringUtils.hasText(context)) {
            contentBuilder.regenerationContext(context);
        }
        if (!sourceReferences.isEmpty()) {
            contentBuilder.sources(sourceReferences);
        }
        sseMessageHelper.sendStreamMessage(messageProtobufQuery, messageProtobufReply, emitter,
                contentBuilder.build().toJson(), null, null, false, true, true);
    }

    private String persistKnowledgeBaseAugmentedSync(String query, RobotProtobuf robot,
            MessageProtobuf messageProtobufQuery, MessageProtobuf messageProtobufReply, long startedAtNanos,
            List<FaqProtobuf> kbResults, List<RobotContent.SourceReference> sourceReferences) {
        String context = promptHelper.buildContextFromFaqs(kbResults);
        List<Message> messages = promptHelper.buildMessagesForSync(query, context, robot,
                messageProtobufQuery);
        Prompt aiPrompt = promptHelper.toPrompt(messages);
        String response = withProviderToolRuntimeContext(robot, messageProtobufQuery,
                () -> processPromptSync(aiPrompt, robot));
        String modelType = robot.getLlm() != null && robot.getLlm().getTextModel() != null
                ? robot.getLlm().getTextModel()
                : "";
        return persistSyncRobotContent(query, robot, messageProtobufQuery, messageProtobufReply,
                response, MessageTypeEnum.ROBOT, false, context, sourceReferences, aiPrompt, "", modelType,
                startedAtNanos, null);
    }

    private String persistToolAugmentedSync(String query, RobotProtobuf robot,
            MessageProtobuf messageProtobufQuery, MessageProtobuf messageProtobufReply, long startedAtNanos,
            String toolContext) {
        List<Message> messages = promptHelper.buildMessagesForSync(query, toolContext, robot,
                messageProtobufQuery);
        Prompt aiPrompt = promptHelper.toPrompt(messages);
        String response = withProviderToolRuntimeContext(robot, messageProtobufQuery,
                () -> processPromptSync(aiPrompt, robot));
        String modelType = robot.getLlm() != null && robot.getLlm().getTextModel() != null
                ? robot.getLlm().getTextModel()
                : "";
        return persistSyncRobotContent(query, robot, messageProtobufQuery, messageProtobufReply,
                response, MessageTypeEnum.ROBOT, false, null, Collections.emptyList(), aiPrompt, "",
                modelType, startedAtNanos, null);
    }

    private String persistKnowledgeBaseOnlySync(String query, RobotProtobuf robot,
            MessageProtobuf messageProtobufQuery, MessageProtobuf messageProtobufReply, long startedAtNanos,
            List<FaqProtobuf> kbResults, List<RobotContent.SourceReference> sourceReferences) {
        FaqProtobuf firstFaq = kbResults.get(0);
        if (kbResults.size() > 1) {
            firstFaq.setRelatedFaqs(new ArrayList<>(kbResults.subList(1, kbResults.size())));
        }
        return persistSyncRobotContent(query, robot, messageProtobufQuery, messageProtobufReply,
                firstFaq.toJson(), MessageTypeEnum.FAQ_ANSWER, false, promptHelper.buildContextFromFaqs(kbResults),
                sourceReferences, null, "", "", startedAtNanos, null);
    }

    private String resolveFallbackReason(KnowledgeIntentRouter.Intent intent, boolean kbEnabled,
            List<FaqProtobuf> kbResults) {
        if (intent == KnowledgeIntentRouter.Intent.CASUAL) {
            return "casual-chat-but-llm-disabled";
        }
        if (!kbEnabled) {
            return "knowledge-base-not-configured";
        }
        return kbResults.isEmpty() ? "knowledge-base-empty" : "answer-policy-fallback";
    }

    protected KnowledgeIntentRouter.Decision classifyKnowledgeIntent(String query, RobotProtobuf robot,
            MessageProtobuf messageProtobufQuery) {
        KnowledgeIntentRouter.Decision ruleDecision = KnowledgeIntentRouter.classifyByRules(query);
        if (ruleDecision.intent() != KnowledgeIntentRouter.Intent.AMBIGUOUS) {
            log.info("Knowledge intent route: intent={}, confidence={}, source=rule, reason={}",
                    ruleDecision.intent(), ruleDecision.confidence(), ruleDecision.reason());
            return ruleDecision;
        }

        RobotToolIntentContext intentContext = robot != null && robot.getLlm() != null
                ? robot.getLlm().getToolIntentContext()
                : null;
        if (intentContext == null || !Boolean.TRUE.equals(intentContext.intentRecognitionEnabled())) {
            KnowledgeIntentRouter.Decision fallback = KnowledgeIntentRouter.defaultKnowledge(
                    "ambiguous-no-intent-model");
            log.info("Knowledge intent route: intent={}, confidence={}, source=default, reason={}",
                    fallback.intent(), fallback.confidence(), fallback.reason());
            return fallback;
        }

        KnowledgeIntentRouter.Decision modelDecision = classifyKnowledgeIntentWithModel(
                query, robot, intentContext, loadIntentHistory(messageProtobufQuery));
        if (modelDecision.intent() == KnowledgeIntentRouter.Intent.CASUAL
                && modelDecision.confidence() < 0.7) {
            modelDecision = KnowledgeIntentRouter.defaultKnowledge("low-confidence-casual");
        }
        log.info("Knowledge intent route: intent={}, confidence={}, source={}, reason={}",
                modelDecision.intent(), modelDecision.confidence(),
                modelDecision.modelAssisted() ? "small-model" : "default", modelDecision.reason());
        return modelDecision;
    }

    private KnowledgeIntentRouter.Decision classifyKnowledgeIntentWithModel(String query, RobotProtobuf robot,
            RobotToolIntentContext intentContext, String history) {
        if (chatClientInfoService == null) {
            return KnowledgeIntentRouter.defaultKnowledge("intent-chat-client-unavailable");
        }
        PiiRedactor.Redaction redaction = PiiRedactor.redact(query);
        String redactedHistory = PiiRedactor.redact(history).text();

        String provider = StringUtils.hasText(intentContext.intentProvider())
                ? intentContext.intentProvider().trim()
                : (robot.getLlm() != null ? robot.getLlm().getTextProvider() : null);
        String model = StringUtils.hasText(intentContext.intentModel())
                ? intentContext.intentModel().trim()
                : (robot.getLlm() != null ? robot.getLlm().getTextModel() : null);
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(model)) {
            return KnowledgeIntentRouter.defaultKnowledge("intent-model-unavailable");
        }

        try {
            ChatClient chatClient = StringUtils.hasText(provider)
                    ? chatClientInfoService.getChatClientByProvider(provider)
                    : null;
            if (chatClient == null) {
                chatClient = chatClientInfoService.getPrimaryChatClient();
            }
            if (chatClient == null) {
                return KnowledgeIntentRouter.defaultKnowledge("intent-chat-client-unavailable");
            }
            final ChatClient resolvedChatClient = chatClient;

            BeanOutputConverter<KnowledgeIntentResult> outputConverter = new BeanOutputConverter<>(
                    KnowledgeIntentResult.class);
            String classifierPrompt = KnowledgeIntentRouter.buildClassifierPrompt(redactedHistory,
                    outputConverter.getFormat());
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(classifierPrompt),
                    new UserMessage(redaction.text())),
                    buildKnowledgeIntentOptions(provider, model.trim(), outputConverter.getJsonSchema()));

            int timeoutMs = sanitizeIntentTimeout(intentContext.intentTimeoutMs());
            KnowledgeIntentResult result = callIntentModel(
                    () -> resolvedChatClient.prompt(prompt).call().entity(KnowledgeIntentResult.class),
                    timeoutMs);
            return KnowledgeIntentRouter.fromModel(
                    result != null ? result.getIntent() : null,
                    result != null ? result.getConfidence() : null,
                    result != null ? result.getReason() : null);
        } catch (TimeoutException e) {
            log.warn("Knowledge intent classification timed out, robotUid={}", robot.getUid());
            return KnowledgeIntentRouter.defaultKnowledge("intent-model-timeout");
        } catch (Exception e) {
            log.warn("Knowledge intent classification failed, robotUid={}, error={}",
                    robot.getUid(), e.getClass().getSimpleName());
            return KnowledgeIntentRouter.defaultKnowledge("intent-model-failed");
        }
    }

    ChatOptions buildKnowledgeIntentOptions(String provider, String model, String jsonSchema) {
        if (LlmProviderConstants.DEEPSEEK.equalsIgnoreCase(provider)) {
            return DeepSeekChatOptions.builder()
                    .model(model)
                    .responseFormat(ResponseFormat.builder()
                            .type(ResponseFormat.Type.JSON_OBJECT)
                            .build())
                    .build();
        }
        if (LlmProviderConstants.OPENAI.equalsIgnoreCase(provider)) {
            return OpenAiChatOptions.builder()
                    .model(model)
                    .outputSchema(jsonSchema)
                    .strict(true)
                    .build();
        }
        return StructuredOutputChatOptions.builder()
                .model(model)
                .outputSchema(jsonSchema)
                .build();
    }

    private int sanitizeIntentTimeout(Integer timeoutMs) {
        if (timeoutMs == null || timeoutMs <= 0) {
            return 1500;
        }
        return Math.max(200, Math.min(timeoutMs, 5000));
    }

    private String loadIntentHistory(MessageProtobuf messageProtobufQuery) {
        if (messageRestService == null || messageProtobufQuery == null
                || messageProtobufQuery.getThread() == null
                || !StringUtils.hasText(messageProtobufQuery.getThread().getTopic())) {
            return "";
        }
        try {
            var recentMessages = messageRestService.getRecentMessages(
                    messageProtobufQuery.getThread().getTopic(), 6);
            if (recentMessages == null || recentMessages.isEmpty()) {
                return "";
            }

            StringBuilder history = new StringBuilder();
            for (var message : recentMessages) {
                if (message == null || !StringUtils.hasText(message.getContent())) {
                    continue;
                }
                String content = message.getContent().trim();
                if (content.length() > 200) {
                    content = content.substring(0, 200);
                }
                if (history.length() + content.length() > 1200) {
                    break;
                }
                if (history.length() > 0) {
                    history.append("\n");
                }
                history.append(content);
            }
            return history.toString();
        } catch (Exception e) {
            log.debug("Failed to load conversation history for intent classification: {}", e.getMessage());
            return "";
        }
    }

    private String persistUnansweredSync(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, long startedAtNanos, String reason) {
        String answer = resolveDefaultAnswer(query, robot, messageProtobufQuery, messageProtobufReply, false,
                resolveRobotDefaultReply(robot));
        return persistSyncRobotContent(query, robot, messageProtobufQuery, messageProtobufReply,
                answer, MessageTypeEnum.ROBOT, true, null, Collections.emptyList(), null, "", "",
                startedAtNanos, reason);
    }

    private String persistClarificationSync(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, long startedAtNanos, String clarification) {
        return persistSyncRobotContent(query, robot, messageProtobufQuery, messageProtobufReply,
                clarification, MessageTypeEnum.ROBOT, false, null, Collections.emptyList(), null, "", "",
                startedAtNanos, null);
    }

    protected BusinessIntentEvaluation classifyBusinessIntent(String query, RobotProtobuf robot,
            MessageProtobuf messageProtobufQuery) {
        Set<String> enabledIntents = null;
        Map<String, Double> thresholds = businessIntentProperties != null
                ? businessIntentProperties.getThresholds()
                : Map.of();
        IntentionSettingsEntity intentionSettings = loadIntentionSettings(robot);
        if (intentionSettings != null && Boolean.TRUE.equals(intentionSettings.getEnabled())) {
            if (intentionSettings.getIntentionList() != null
                    && !intentionSettings.getIntentionList().isEmpty()) {
                enabledIntents = BusinessIntentRegistry.configuredTypes(intentionSettings.getIntentionList());
            } else {
                log.warn("Robot intention settings enabled but list is empty, keeping built-in intents, robotUid={}",
                        robot.getUid());
            }
            Double robotThreshold = intentionSettings.getConfidenceThreshold();
            if (robotThreshold != null && robotThreshold >= 0.0 && robotThreshold <= 1.0
                    && enabledIntents != null && !enabledIntents.isEmpty()) {
                thresholds = new LinkedHashMap<>(thresholds);
                for (String type : enabledIntents) {
                    thresholds.merge(type, robotThreshold, Math::max);
                }
            }
        }
        BusinessIntentResult ruleResult = BusinessIntentRegistry.filterEnabledIntents(
                BusinessIntentRegistry.classifyByRules(query), enabledIntents);
        BusinessIntentEvaluation ruleEvaluation = BusinessIntentRegistry.evaluate(ruleResult, thresholds);
        if (ruleEvaluation.action() == BusinessIntentAction.DIRECT_LLM) {
            return ruleEvaluation;
        }

        RobotToolIntentContext intentContext = robot != null && robot.getLlm() != null
                ? robot.getLlm().getToolIntentContext()
                : null;
        if (intentContext == null || !Boolean.TRUE.equals(intentContext.intentRecognitionEnabled())) {
            log.info("Business intent route: intents={}, action={}, source=rule, missing={}",
                    ruleEvaluation.intentSummary(), ruleEvaluation.action(), ruleEvaluation.missingSlots());
            return ruleEvaluation;
        }

        BusinessIntentResult modelResult = classifyBusinessIntentWithModel(
                query, robot, intentContext, loadIntentHistory(messageProtobufQuery), enabledIntents);
        modelResult = BusinessIntentRegistry.filterEnabledIntents(modelResult, enabledIntents);
        if (modelResult == null || modelResult.getIntents() == null || modelResult.getIntents().isEmpty()) {
            log.info("Business intent route: intents={}, action={}, source=rule-fallback, missing={}",
                    ruleEvaluation.intentSummary(), ruleEvaluation.action(), ruleEvaluation.missingSlots());
            return ruleEvaluation;
        }
        BusinessIntentResult merged = BusinessIntentRegistry.mergeModelWithRules(modelResult, ruleResult);
        if (merged != null) {
            BusinessIntentEvaluation modelEvaluation = BusinessIntentRegistry.evaluate(merged, thresholds);
            log.info("Business intent route: intents={}, action={}, source=model, missing={}",
                    modelEvaluation.intentSummary(), modelEvaluation.action(), modelEvaluation.missingSlots());
            return modelEvaluation;
        }

        log.info("Business intent route: intents={}, action={}, source=rule-fallback, missing={}",
                ruleEvaluation.intentSummary(), ruleEvaluation.action(), ruleEvaluation.missingSlots());
        return ruleEvaluation;
    }

    private IntentionSettingsEntity loadIntentionSettings(RobotProtobuf robot) {
        if (robotRestService == null || robot == null || !StringUtils.hasText(robot.getUid())) {
            return null;
        }
        try {
            var robotEntity = robotRestService.findByUid(robot.getUid());
            if (robotEntity == null || robotEntity.isEmpty() || robotEntity.get().getSettings() == null) {
                return null;
            }
            return robotEntity.get().getSettings().getIntentionSettings();
        } catch (Exception e) {
            log.warn("Unable to load robot intention settings, robotUid={}, error={}",
                    robot.getUid(), e.getClass().getSimpleName());
            return null;
        }
    }

    private boolean hasBusinessIntent(BusinessIntentEvaluation evaluation, BusinessIntentType expected) {
        return evaluation != null && evaluation.intents() != null && evaluation.intents().stream()
                .anyMatch(intent -> intent != null
                        && expected.name().equalsIgnoreCase(intent.getType()));
    }

    private BusinessIntentEvaluation withOnlyIntent(BusinessIntentEvaluation evaluation,
            BusinessIntentType expected) {
        List<BusinessIntentMatch> matches = evaluation == null || evaluation.intents() == null ? List.of()
                : evaluation.intents().stream()
                        .filter(intent -> intent != null && expected.name().equalsIgnoreCase(intent.getType()))
                        .toList();
        return new BusinessIntentEvaluation(matches, BusinessIntentAction.TOOL, List.of(), null,
                "business-tool-subset:" + expected.name());
    }

    private BusinessToolExecutor.InvocationContext businessToolInvocation(MessageProtobuf queryMessage) {
        if (queryMessage == null) {
            return BusinessToolExecutor.InvocationContext.empty();
        }
        String threadUid = queryMessage.getThread() != null ? queryMessage.getThread().getUid() : null;
        return new BusinessToolExecutor.InvocationContext(
                queryMessage.getUid(), threadUid, queryMessage.getUid());
    }

    private void recordBusinessIntentMetrics(BusinessIntentEvaluation evaluation) {
        if (bytedeskMetrics != null && evaluation != null) {
            bytedeskMetrics.businessIntentClassified(evaluation.action().name(), evaluation.intentSummary());
        }
    }

    private void recordBusinessClarification(BusinessIntentEvaluation evaluation) {
        if (bytedeskMetrics != null && evaluation != null) {
            bytedeskMetrics.businessIntentClarification(evaluation.intentSummary(),
                    String.join(",", evaluation.missingSlots()));
        }
    }

    private void recordBusinessTransfer(BusinessIntentEvaluation evaluation, String reason) {
        if (bytedeskMetrics != null && evaluation != null) {
            bytedeskMetrics.businessTransfer(evaluation.intentSummary(), reason);
        }
    }

    private void recordBusinessTool(BusinessIntentEvaluation evaluation, boolean success, long startedAtNanos) {
        if (bytedeskMetrics != null && evaluation != null) {
            bytedeskMetrics.businessToolExecuted(evaluation.intentSummary(), success, elapsedMillis(startedAtNanos));
        }
    }

    private BusinessIntentResult classifyBusinessIntentWithModel(String query, RobotProtobuf robot,
            RobotToolIntentContext intentContext, String history, Set<String> enabledIntents) {
        if (chatClientInfoService == null) {
            return null;
        }
        PiiRedactor.Redaction redaction = PiiRedactor.redact(query);
        String redactedHistory = PiiRedactor.redact(history).text();
        String provider = StringUtils.hasText(intentContext.intentProvider())
                ? intentContext.intentProvider().trim()
                : (robot.getLlm() != null ? robot.getLlm().getTextProvider() : null);
        String model = StringUtils.hasText(intentContext.intentModel())
                ? intentContext.intentModel().trim()
                : (robot.getLlm() != null ? robot.getLlm().getTextModel() : null);
        if (!StringUtils.hasText(provider) || !StringUtils.hasText(model)) {
            return null;
        }

        try {
            ChatClient chatClient = chatClientInfoService.getChatClientByProvider(provider);
            if (chatClient == null) {
                chatClient = chatClientInfoService.getPrimaryChatClient();
            }
            if (chatClient == null) {
                return null;
            }
            final ChatClient resolvedChatClient = chatClient;
            BeanOutputConverter<BusinessIntentResult> converter = new BeanOutputConverter<>(
                    BusinessIntentResult.class);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(BusinessIntentRegistry.buildClassifierPrompt(
                            redactedHistory, converter.getFormat())),
                    new UserMessage(redaction.text())),
                    buildKnowledgeIntentOptions(provider, model, converter.getJsonSchema()));
            int timeoutMs = sanitizeIntentTimeout(intentContext.intentTimeoutMs());
            BusinessIntentResult result = callIntentModel(
                    () -> resolvedChatClient.prompt(prompt).call().entity(BusinessIntentResult.class),
                    timeoutMs);
            result = BusinessIntentRegistry.filterEnabledIntents(result, enabledIntents);
            return enrichBusinessSlotsWithModel(redaction, redactedHistory, result, resolvedChatClient,
                    provider, model, timeoutMs);
        } catch (TimeoutException e) {
            log.warn("Business intent classification timed out, robotUid={}", robot.getUid());
            return null;
        } catch (Exception e) {
            log.warn("Business intent classification failed, robotUid={}, error={}",
                    robot.getUid(), e.getClass().getSimpleName());
            return null;
        }
    }

    private BusinessIntentResult enrichBusinessSlotsWithModel(PiiRedactor.Redaction redaction, String history,
            BusinessIntentResult result, ChatClient chatClient, String provider, String model, int timeoutMs) {
        if (result == null || result.getIntents() == null || result.getIntents().isEmpty()) {
            return result;
        }
        int calls = 0;
        for (BusinessIntentMatch match : result.getIntents()) {
            int maxCalls = businessIntentProperties != null
                    ? Math.max(1, Math.min(businessIntentProperties.getMaxSlotExtractionCalls(), 6))
                    : 3;
            if (match == null || calls >= maxCalls) {
                break;
            }
            BusinessIntentDefinition definition = BusinessIntentRegistry.definitionFor(match.getType());
            if (definition == null || definition.slots().isEmpty()
                    || definition.action() == BusinessIntentAction.DIRECT_LLM) {
                continue;
            }
            BeanOutputConverter<BusinessSlotResult> converter = new BeanOutputConverter<>(
                    BusinessSlotResult.class);
            String slotPrompt = BusinessIntentRegistry.buildSlotExtractionPrompt(
                    definition, history, converter.getFormat());
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(slotPrompt),
                    new UserMessage(redaction.text())),
                    buildKnowledgeIntentOptions(provider, model, converter.getJsonSchema()));
            calls++;
            try {
                BusinessSlotResult slotResult = callIntentModel(
                        () -> chatClient.prompt(prompt).call().entity(BusinessSlotResult.class),
                        timeoutMs);
                List<BusinessSlot> slots = BusinessIntentRegistry.sanitizeSlotResult(
                        restoreSlotResult(slotResult, redaction), definition.type());
                if (slots != null && !slots.isEmpty()) {
                    match.setSlots(slots);
                }
            } catch (Exception e) {
                log.warn("Business slot extraction failed, intent={}, error={}",
                        match.getType(), e.getClass().getSimpleName());
            }
        }
        return result;
    }

    private BusinessSlotResult restoreSlotResult(BusinessSlotResult source, PiiRedactor.Redaction redaction) {
        if (source == null || redaction == null || redaction.replacements().isEmpty()) {
            return source;
        }
        List<BusinessSlot> slots = source.getSlots() == null ? List.of() : source.getSlots().stream()
                .map(slot -> slot == null ? null
                        : new BusinessSlot(slot.getName(), redaction.restore(slot.getValue())))
                .toList();
        return new BusinessSlotResult(source.getIntent(), slots, redaction.restore(source.getReason()));
    }

    private <T> T callIntentModel(Callable<T> callable, int timeoutMs) throws Exception {
        Future<T> future = INTENT_MODEL_EXECUTOR.submit(callable);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private String persistSyncRobotContent(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, String answer, MessageTypeEnum messageType, boolean unanswered,
            String regenerationContext, List<RobotContent.SourceReference> sourceReferences, Prompt prompt,
            String aiProvider, String aiModel, long startedAtNanos, String unansweredReason) {
        RobotContent.RobotContentBuilder<?, ?> contentBuilder = RobotContent.builder()
                .question(query)
                .answer(answer)
                .kbUid(robot.getKbUid())
                .robotUid(robot.getUid());
        if (StringUtils.hasText(regenerationContext)) {
            contentBuilder.regenerationContext(regenerationContext);
        }
        if (sourceReferences != null && !sourceReferences.isEmpty()) {
            contentBuilder.sources(sourceReferences);
        }

        messageProtobufReply.setContent(contentBuilder.build().toJson());
        messageProtobufReply.setType(messageType);
        messagePersistenceHelper.persistMessage(messageProtobufQuery, messageProtobufReply, unanswered,
                elapsedMillis(startedAtNanos), 0, 0, 0, prompt, aiProvider, aiModel);
        messageSendService.sendProtobufMessage(messageProtobufReply);
        if (unanswered) {
            publishUnansweredTransferEvent(robot, messageProtobufQuery, unansweredReason);
        }
        return answer;
    }

    protected long elapsedMillis(long startedAtNanos) {
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        return elapsedNanos > 0 ? elapsedNanos / 1_000_000L : 0L;
    }

    protected <T> T withProviderToolRuntimeContext(RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            ProviderToolInvocation<T> invocation) {
        Map<String, Object> previous = providerToolRuntimeContextHolder.get();
        Map<String, Object> context = new HashMap<>();
        if (previous != null && !previous.isEmpty()) {
            context.putAll(previous);
        }
        if (robot != null) {
            if (StringUtils.hasText(robot.getUid())) {
                context.put("robotUid", robot.getUid());
            }
            if (StringUtils.hasText(robot.getOrgUid())) {
                context.put("orgUid", robot.getOrgUid());
            }
        }
        if (messageProtobufQuery != null) {
            if (StringUtils.hasText(messageProtobufQuery.getUid())) {
                context.put("messageUid", messageProtobufQuery.getUid());
            }
            if (messageProtobufQuery.getThread() != null
                    && StringUtils.hasText(messageProtobufQuery.getThread().getUid())) {
                context.put("threadUid", messageProtobufQuery.getThread().getUid());
            }
        }

        providerToolRuntimeContextHolder.set(Collections.unmodifiableMap(context));
        try {
            return invocation.invoke();
        } finally {
            if (previous == null || previous.isEmpty()) {
                providerToolRuntimeContextHolder.remove();
            } else {
                providerToolRuntimeContextHolder.set(previous);
            }
        }
    }

    @Override
    public String processSyncRequest(String query, RobotProtobuf robot, boolean searchKnowledgeBase) {
        // 检查是否启用大模型
        if (robot.getLlm() == null || !robot.getLlm().getEnabled()) {
            log.warn("LLM未启用，无法处理直接LLM请求");
            return I18Consts.I18N_SORRY_LLM_DISABLED;
        }

        String prompt = robot.getLlm().getPrompt();
        log.info("处理直接LLM请求: queryLength={}, promptConfigured={}, robot={}, searchKnowledgeBase={}",
                query != null ? query.length() : 0, StringUtils.hasText(prompt), robot.getUid(),
                searchKnowledgeBase);

        List<FaqProtobuf> searchResultList = new ArrayList<>();

        // 根据参数决定是否查询知识库
        if (searchKnowledgeBase) {
            searchResultList = searchKnowledgeBaseRag(query, robot);
            log.info("processDirectLlmRequest searchResultCount={}", searchResultList.size());
        } else {
            log.info("跳过知识库查询，直接使用提示词处理");
        }

        // 构建提示词
        String context = "";
        if (!searchResultList.isEmpty()) {
            context = String.join("\n", searchResultList.stream().map(faq -> faq.toJson()).toList());
            log.info("processDirectLlmRequest contextLength={}", context.length());
        }

        List<Message> messages = promptHelper.buildMessagesForSync(query, context, robot, null);
        Prompt aiPrompt = promptHelper.toPrompt(messages);

        // 调用子类实现的处理方法
        try {
            String response = withProviderToolRuntimeContext(robot, null,
                    () -> processPromptSync(aiPrompt, robot));
            log.info("processDirectLlmRequest responseLength={}", response != null ? response.length() : 0);
            if (response != null && response.contains("<think>")) {
                response = response.replaceAll("(?s)<think>.*?</think>", "");
            }
            return response;
        } catch (Exception e) {
            log.error("处理LLM请求失败", e);
            return I18Consts.I18N_SORRY_SERVICE_UNAVAILABLE;
        }
    }

    /**
     * 结构化输出：在保留现有 prompt 逻辑的前提下，
     * 通过 ChatClient.entity(outputClass) 直接将 LLM 输出转换为 POJO。
     *
     * <p>
     * 使用方需自行在 query 中加入 BeanOutputConverter.getFormat() 等格式约束。
     * </p>
     */
    public <T> T processSyncRequest(String query, RobotProtobuf robot, Class<T> outputClass) {
        return processSyncRequest(query, robot, null, null, outputClass);
    }

    protected <T> T processSyncRequest(String query, RobotProtobuf robot, String providerOverride,
            String modelOverride, Class<T> outputClass) {
        Assert.hasText(query, "Query must not be empty");
        Assert.notNull(outputClass, "OutputClass must not be null");

        // 检查是否启用大模型
        if (robot.getLlm() == null || !robot.getLlm().getEnabled()) {
            log.warn("LLM未启用，无法处理直接LLM请求");
            return null;
        }

        String prompt = robot.getLlm().getPrompt();
        log.info("处理直接LLM请求(结构化): queryLength={}, promptConfigured={}, robot={}, outputClass={}",
                query.length(), StringUtils.hasText(prompt), robot.getUid(), outputClass.getSimpleName());

        StringBuilder aiPrompt = new StringBuilder();
        if (StringUtils.hasText(prompt)) {
            aiPrompt.append(prompt);
        } else {
            aiPrompt.append(I18Consts.I18N_CONTEXT_BASED_ANSWER);
            aiPrompt.append(query);
        }

        try {
            if (chatClientInfoService == null) {
                throw new IllegalStateException("ChatClientInfoService is not available");
            }

            String provider = StringUtils.hasText(providerOverride)
                    ? providerOverride.trim()
                    : (robot != null && robot.getLlm() != null && StringUtils.hasText(robot.getLlm().getTextProvider())
                            ? robot.getLlm().getTextProvider()
                            : null);

            ChatClient chatClient = StringUtils.hasText(provider)
                    ? chatClientInfoService.getChatClientByProvider(provider)
                    : null;
            if (chatClient == null) {
                chatClient = chatClientInfoService.getPrimaryChatClient();
            }

            if (chatClient == null) {
                throw new IllegalStateException("ChatClient is not available for provider: " + provider);
            }

            Prompt structuredPrompt = StringUtils.hasText(modelOverride)
                    ? new Prompt(List.of(new SystemMessage(aiPrompt.toString()), new UserMessage(query)),
                            ChatOptions.builder().model(modelOverride.trim()).build())
                    : new Prompt(List.of(new SystemMessage(aiPrompt.toString()), new UserMessage(query)));
            return chatClient.prompt(structuredPrompt).call().entity(outputClass);
        } catch (Exception e) {
            log.error("处理LLM请求失败(结构化)", e);
            return null;
        }
    }

    protected Prompt processPromptWithOptions(Prompt prompt, ChatOptions options) {
        if (prompt == null || options == null) {
            return prompt;
        }
        return new Prompt(prompt.getInstructions(), options);
    }

    protected Prompt buildUserOnlyPrompt(String message, ChatOptions options) {
        return options != null ? new Prompt(List.of(new UserMessage(message)), options)
                : new Prompt(List.of(new UserMessage(message)));
    }

    /**
     * 构建 ChatClient（保留旧签名，向后兼容）。
     *
     * <p>内部委托给 {@link #createChatClient(ChatModel, Prompt, RobotProtobuf)}，
     * 以 {@code robot=null} 调用，即不注入任何 Advisor，行为与改造前完全一致。</p>
     *
     * @deprecated 请改用 {@link #createChatClient(ChatModel, Prompt, RobotProtobuf)}，
     *             以便根据机器人配置注入 SafeGuard / Logging / Memory 等 Advisor。
     */
    @Deprecated
    protected ChatClient createChatClient(ChatModel chatModel, Prompt prompt) {
        return createChatClient(chatModel, prompt, null);
    }

    /**
     * 构建 ChatClient 并根据机器人配置注入 Advisor 链。
     *
     * <p>当 {@code robot != null} 时，会调用 {@link #buildAdvisorChain(RobotProtobuf)}
     * 组装 Advisor 链（SafeGuard / ReReading / Logging / Memory 等），并通过
     * {@link ChatClient.Builder#defaultAdvisors(java.util.List)} 注入；当
     * {@code robot == null} 时 {@link #buildAdvisorChain(RobotProtobuf)} 返回空 list，
     * 行为与改造前完全一致，保证向后兼容。</p>
     *
     * <p>子类无需逐一手动修改调用点：旧签名 {@link #createChatClient(ChatModel, Prompt)}
     * 已 {@code @Deprecated} 并委托本方法；按 provider 分批迁移到本重载即可逐步启用 Advisor。</p>
     */
    protected ChatClient createChatClient(ChatModel chatModel, Prompt prompt, RobotProtobuf robot) {
        Assert.notNull(chatModel, "ChatModel must not be null");

        ChatClient.Builder builder = chatClientBuilderFactory != null
            ? chatClientBuilderFactory.builder(chatModel)
            : ChatClient.builder(chatModel);
        if (prompt != null && prompt.getOptions() != null) {
            builder = builder.defaultOptions(prompt.getOptions().mutate());
        }
        // 注入 Advisor 链（根据 RobotProtobuf 配置，robot=null 时返回空 list，完全向后兼容）
        List<Advisor> advisors = buildAdvisorChain(robot);
        if (!advisors.isEmpty()) {
            builder = builder.defaultAdvisors(advisors);
        }
        return builder.build();
    }

    /**
     * 组装 Advisor 链（委托给 {@link AdvisorChainFactory}）。
     *
     * <p>实现细节（SafeGuard / ReReading / Logging / Memory 组装、敏感词缓存、order 推导等）
     * 已抽取到 {@link AdvisorChainFactory}，基类仅保留此委托入口以便子类按需 override。</p>
     *
     * <p>当工厂 bean 不存在（测试环境等）时返回空 list，保证行为零变化。</p>
     *
     * @param robot 机器人配置（含 llm 配置）；{@code null} 表示不注入任何 Advisor
     * @return 组装后的 Advisor 链；无 Advisor 时返回空 list
     */
    protected List<Advisor> buildAdvisorChain(RobotProtobuf robot) {
        if (advisorChainFactory == null) {
            return Collections.emptyList();
        }
        return advisorChainFactory.buildAdvisorChain(robot);
    }

    // ===== 阶段4：RAG Query 增强（KB 检索前改写/扩展 query，不替代 KB 搜索）=====
    // 以下两个方法仅在 robot.llm.ragRewriteEnabled/ragMultiQueryEnabled=true 时生效，
    // 否则（含 ragQueryRewriteHelper==null）直接委托 KnowledgeBaseSearchHelper，行为零变化。

    /**
     * 带 RAG Query 增强的 KB 检索（带来源引用）。
     *
     * <p>开关关闭或 {@code ragQueryRewriteHelper} 不可用时，等价于
     * {@code knowledgeBaseSearchHelper.searchKnowledgeBaseWithSources(query, robot)}。</p>
     */
    protected SearchResultWithSources searchKnowledgeBaseWithSourcesRag(String query, RobotProtobuf robot) {
        return searchKnowledgeBaseWithSourcesRag(query, robot, null, null);
    }

    /**
     * 带 RAG Query 增强的 KB 检索（带来源引用，含上下文用于持久化记录）。
     *
     * <p>开关关闭或 {@code ragQueryRewriteHelper} 不可用时，等价于
     * {@code knowledgeBaseSearchHelper.searchKnowledgeBaseWithSources(query, robot)}。</p>
     *
     * @param threadTopic 会话主题（可为空，用于 RAG 记录持久化）
     * @param messageUid  触发消息 UID（可为空，用于 RAG 记录持久化）
     */
    protected SearchResultWithSources searchKnowledgeBaseWithSourcesRag(String query, RobotProtobuf robot,
            String threadTopic, String messageUid) {
        if (ragQueryRewriteHelper == null) {
            // 诊断：helper bean 未注入时给出明确警告（避免静默降级难以排查）
            log.warn("searchKnowledgeBaseWithSourcesRag: ragQueryRewriteHelper is null (bean not injected), "
                    + "falling back to plain KB search. RAG switches will NOT take effect.");
            return kbaseSearchHelper.searchKnowledgeBaseWithSources(query, robot);
        }
        return ragQueryRewriteHelper.searchWithRagEnhancement(query, robot, threadTopic, messageUid);
    }

    /**
     * 带 RAG Query 增强的 KB 检索（仅 Faq 列表，兼容返回 {@code List<FaqProtobuf>} 的调用点）。
     *
     * <p>开关关闭或 {@code ragQueryRewriteHelper} 不可用时，等价于
     * {@code knowledgeBaseSearchHelper.searchKnowledgeBase(query, robot)}。</p>
     */
    protected List<FaqProtobuf> searchKnowledgeBaseRag(String query, RobotProtobuf robot) {
        return searchKnowledgeBaseRag(query, robot, null, null);
    }

    /**
     * 带 RAG Query 增强的 KB 检索（仅 Faq 列表，含上下文用于持久化记录）。
     *
     * <p>开关关闭或 {@code ragQueryRewriteHelper} 不可用时，等价于
     * {@code knowledgeBaseSearchHelper.searchKnowledgeBase(query, robot)}。</p>
     *
     * @param threadTopic 会话主题（可为空，用于 RAG 记录持久化）
     * @param messageUid  触发消息 UID（可为空，用于 RAG 记录持久化）
     */
    protected List<FaqProtobuf> searchKnowledgeBaseRag(String query, RobotProtobuf robot,
            String threadTopic, String messageUid) {
        if (ragQueryRewriteHelper == null) {
            log.warn("searchKnowledgeBaseRag: ragQueryRewriteHelper is null (bean not injected), "
                    + "falling back to plain KB search. RAG switches will NOT take effect.");
            return kbaseSearchHelper.searchKnowledgeBase(query, robot);
        }
        return ragQueryRewriteHelper.searchKnowledgeBaseWithRag(query, robot, threadTopic, messageUid);
    }

    /**
     * 从 messageProtobuf 提取 conversationId（= threadTopic，与 PromptHelper 手动历史同键，见规划 v3.2）。
     * messageProtobufQuery 或其 thread 为 null 时返回 null（健康检查、无 thread 请求走无记忆路径）。
     */
    protected String extractConversationId(MessageProtobuf messageProtobufQuery) {
        if (messageProtobufQuery == null || messageProtobufQuery.getThread() == null) {
            return null;
        }
        return messageProtobufQuery.getThread().getTopic();
    }

    /**
     * 在 ThreadLocal 中设置 Sync 路径的 conversationId，并执行 invocation，结束后清除。
     * 供 sendSyncMessage 等同步入口使用，使下游 2 参 invokePromptSync 自动获得 conversationId。
     */
    protected <T> T withConversationId(String conversationId, ProviderToolInvocation<T> invocation) {
        String previous = conversationIdHolder.get();
        if (StringUtils.hasText(conversationId)) {
            conversationIdHolder.set(conversationId);
        } else {
            conversationIdHolder.remove();
        }
        try {
            return invocation.invoke();
        } finally {
            if (previous != null) {
                conversationIdHolder.set(previous);
            } else {
                conversationIdHolder.remove();
            }
        }
    }

    /**
     * 同步调用（2 参）。conversationId 从 {@link #conversationIdHolder} ThreadLocal 读取（Sync 路径）。
     *
     * <p>Sync 是同步同线程，sendSyncMessage 在入口设置 ThreadLocal，本方法读取后传给 3 参重载。
     * SSE 不走本方法（异步跨线程，ThreadLocal 不可靠），用显式 {@link #invokePromptStream(ChatClient, Prompt, String)}。</p>
     */
    protected ChatResponse invokePromptSync(ChatClient chatClient, Prompt prompt) {
        return invokePromptSync(chatClient, prompt, conversationIdHolder.get());
    }

    /**
     * 同步调用并可选地传入 conversationId（用于 Memory Advisor 的会话隔离）。
     *
     * <p>conversationId 为空时自动兜底为一次性随机 ID：当 advisor 链包含 MessageChatMemoryAdvisor
     * （robot.llm.memoryEnabled=true）时，Spring AI 要求 CONVERSATION_ID 非空，否则 Assert 直接抛
     * IllegalArgumentException 导致整个请求失败（2026-09-14 子租户 robot_agent 同步任务报错根因）。
     * 一次性随机 ID 语义等同“无记忆”：读取历史为空、不与其他会话串扰。</p>
     *
     * @param conversationId 会话标识，约定取 {@code threadTopic}（与 PromptHelper 手动历史同键，见规划 v3.2）
     */
    protected ChatResponse invokePromptSync(ChatClient chatClient, Prompt prompt, String conversationId) {
        Assert.notNull(chatClient, "ChatClient must not be null");
        var request = prompt != null ? chatClient.prompt(prompt) : chatClient.prompt();
        request = request.advisors(
                a -> a.param(ChatMemory.CONVERSATION_ID, resolveConversationIdOrDefault(conversationId)));
        return request.call().chatResponse();
    }

    protected ChatResponse invokePromptSync(ChatModel chatModel, Prompt prompt) {
        return invokePromptSync(createChatClient(chatModel, prompt), prompt);
    }

    protected Flux<ChatResponse> invokePromptStream(ChatClient chatClient, Prompt prompt) {
        return invokePromptStream(chatClient, prompt, null);
    }

    /**
     * 流式调用并可选地传入 conversationId（用于 Memory Advisor 的会话隔离）。
     *
     * <p>conversationId 为空时兜底一次性随机 ID，语义等同“无记忆”，原因见
     * {@link #invokePromptSync(ChatClient, Prompt, String)}。</p>
     */
    protected Flux<ChatResponse> invokePromptStream(ChatClient chatClient, Prompt prompt, String conversationId) {
        Assert.notNull(chatClient, "ChatClient must not be null");
        var request = prompt != null ? chatClient.prompt(prompt) : chatClient.prompt();
        request = request.advisors(
                a -> a.param(ChatMemory.CONVERSATION_ID, resolveConversationIdOrDefault(conversationId)));
        return request.stream().chatResponse();
    }

    /**
     * conversationId 为空时的兜底：生成一次性随机会话 ID。
     *
     * <p>MessageChatMemoryAdvisor 处于 advisor 链时要求 CONVERSATION_ID 非空；一次性随机 ID 读取历史为空、
     * 不与其他会话串扰（仅在记忆表落当前请求的两条记录）。</p>
     */
    private static String resolveConversationIdOrDefault(String conversationId) {
        return StringUtils.hasText(conversationId) ? conversationId
                : "oneshot-" + java.util.UUID.randomUUID();
    }

    protected Flux<ChatResponse> invokePromptStream(ChatModel chatModel, Prompt prompt) {
        return invokePromptStream(createChatClient(chatModel, prompt), prompt);
    }

    protected String processPromptSync(Prompt prompt, RobotProtobuf robot) {
        return processPromptSync(promptHelper != null ? promptHelper.flattenPromptText(prompt) : null, robot);
    }

    protected abstract void processPromptSse(Prompt prompt, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, List<RobotContent.SourceReference> sourceReferences,
            SseEmitter emitter);

    protected String resolveDefaultAnswer(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, boolean streaming, String defaultAnswer) {
        if (agentCannedResponseResolver == null) {
            return defaultAnswer;
        }
        Map<String, String> evidenceFields = buildCannedResponseEvidenceFields(query, messageProtobufQuery,
                defaultAnswer);
        AgentCannedResponseMatch match = agentCannedResponseResolver.resolve(
                new AgentCannedResponseRequest(query, robot, messageProtobufQuery, evidenceFields, streaming,
                        defaultAnswer));
        if (match == null || !StringUtils.hasText(match.answer())) {
            return defaultAnswer;
        }
        if (agentCannedResponseTraceContext != null && messageProtobufReply != null) {
            agentCannedResponseTraceContext.store(messageProtobufReply.getUid(), match.cannedResponseUid());
        }
        return match.answer();
    }

    protected String resolveRobotDefaultReply(RobotProtobuf robot) {
        String reply = robot.getLlm() != null && robot.getLlm().getDefaultReply() != null
                ? robot.getLlm().getDefaultReply()
                : I18Consts.I18N_ROBOT_DEFAULT_REPLY;
        // 将 i18n key（如 "i18n.robot.noreply"）解析为本地化文本，避免 key 直接泄漏给前端
        // return I18nTextResolver.resolveIfKey(reply);
        return reply;
    }

    private void sendUnansweredSse(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, SseEmitter emitter, String reason) {
        String answer = resolveDefaultAnswer(query, robot, messageProtobufQuery, messageProtobufReply, true,
                resolveRobotDefaultReply(robot));
        sseMessageHelper.sendDefaultReplySse(query, answer, robot, messageProtobufQuery, messageProtobufReply,
                emitter);
        publishUnansweredTransferEvent(robot, messageProtobufQuery, reason);
    }

    private void sendClarificationSse(String query, RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            MessageProtobuf messageProtobufReply, SseEmitter emitter, String clarification) {
        sseMessageHelper.sendDefaultReplySse(query, clarification, robot, messageProtobufQuery,
                messageProtobufReply, emitter);
    }

    private List<RobotContent.SourceReference> visibleSourceReferences(RobotProtobuf robot,
            SearchResultWithSources searchResult) {
        if (searchResult == null || !Boolean.TRUE.equals(robot.getKbSourceEnabled())) {
            return new ArrayList<>();
        }
        return searchResult.getSourceReferences() != null
                ? searchResult.getSourceReferences()
                : new ArrayList<>();
    }

    private void publishUnansweredTransferEvent(RobotProtobuf robot, MessageProtobuf messageProtobufQuery,
            String reason) {
        if (applicationEventPublisher == null || messageProtobufQuery == null
                || messageProtobufQuery.getThread() == null
                || !StringUtils.hasText(messageProtobufQuery.getThread().getUid())) {
            return;
        }
        try {
            applicationEventPublisher.publishEvent(new RobotUnansweredEvent(
                    this,
                    messageProtobufQuery.getThread().getUid(),
                    messageProtobufQuery.toJson(),
                    robot != null ? robot.getUid() : null,
                    reason));
        } catch (Exception e) {
            log.warn("Failed to publish robot-unanswered transfer event, threadUid={}, reason={}",
                    messageProtobufQuery.getThread().getUid(), reason, e);
        }
    }

    protected Map<String, String> buildCannedResponseEvidenceFields(String query, MessageProtobuf messageProtobufQuery,
            String defaultAnswer) {
        if (cannedResponseEvidenceHelper == null) {
            Map<String, String> fallback = new LinkedHashMap<>();
            if (query != null) {
                fallback.put("query", query);
            }
            return fallback;
        }
        return cannedResponseEvidenceHelper.buildEvidenceFields(query, messageProtobufQuery, defaultAnswer);
    }

    @FunctionalInterface
    protected interface ProviderToolInvocation<T> {
        T invoke();
    }

    // 带prompt参数的抽象方法重载
    protected abstract String processPromptSync(String message, RobotProtobuf robot);

}
