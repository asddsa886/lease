package com.atguigu.lease.web.app.assistant.service.chat;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.config.AssistantProperties;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatRequest;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import com.atguigu.lease.web.app.assistant.dto.AssistantStreamPayload;
import com.atguigu.lease.web.app.assistant.dto.AssistantTaskState;
import com.atguigu.lease.web.app.assistant.service.agent.AbstractAssistantAgent;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantAgentRoute;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantRoutingPolicy;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSupervisorAgent;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSupervisorDecision;
import com.atguigu.lease.web.app.assistant.service.agent.BusinessExecutionAssistantAgent;
import com.atguigu.lease.web.app.assistant.service.agent.SearchQaAssistantAgent;
import com.atguigu.lease.web.app.assistant.service.memory.AssistantLongTermMemoryService;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationSessionService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolContextSupport;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class MultiAgentAssistantService implements AppAssistantService {

    private final AssistantPromptService promptService;
    private final AssistantConversationSessionService conversationSessionService;
    private final AssistantLongTermMemoryService longTermMemoryService;
    private final AssistantProperties assistantProperties;
    private final AssistantSupervisorAgent supervisorAgent;
    private final Map<AssistantAgentRoute, AbstractAssistantAgent> agents;

    public MultiAgentAssistantService(ChatModel chatModel,
                                      ObjectMapper objectMapper,
                                      AssistantPromptService promptService,
                                      AssistantConversationSessionService conversationSessionService,
                                      AssistantProperties assistantProperties,
                                      AssistantApartmentTools apartmentTools,
                                      AssistantRoomTools roomTools,
                                      AssistantBrowsingHistoryTools browsingHistoryTools,
                                      AssistantAppointmentTools appointmentTools,
                                      AssistantLeaseOrderTools leaseOrderTools,
                                      AssistantKnowledgeTools knowledgeTools,
                                      AssistantLongTermMemoryService longTermMemoryService) {
        this.promptService = promptService;
        this.conversationSessionService = conversationSessionService;
        this.longTermMemoryService = longTermMemoryService;
        this.assistantProperties = assistantProperties;
        this.supervisorAgent = new AssistantSupervisorAgent(chatModel, objectMapper, new AssistantRoutingPolicy());
        this.agents = new EnumMap<>(AssistantAgentRoute.class);
        this.agents.put(AssistantAgentRoute.SEARCH_QA,
                new SearchQaAssistantAgent(chatModel, apartmentTools, roomTools, browsingHistoryTools, knowledgeTools));
        this.agents.put(AssistantAgentRoute.BUSINESS_EXECUTION,
                new BusinessExecutionAssistantAgent(chatModel, appointmentTools, leaseOrderTools, apartmentTools, roomTools, knowledgeTools));
    }

    @Override
    public AssistantChatResponse chat(AssistantChatRequest request, LoginUser currentUser) {
        String userMessage = request.getMessage().trim();
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        List<AssistantConversationMessage> history = conversationSessionService.getMessages(currentUser.getId(), conversationId);
        String longTermMemoryPrompt = prepareLongTermMemory(currentUser.getId(), userMessage);
        AssistantSupervisorDecision decision = supervisorAgent.decide(currentUser, history, userMessage);
        AbstractAssistantAgent agent = resolveAgent(decision);

        try {
            String reply = agent.chat(
                    currentUser,
                    history,
                    userMessage,
                    decision,
                    promptService,
                    longTermMemoryPrompt,
                    buildToolContext(currentUser, conversationId, AssistantToolEventEmitter.noop())
            );

            AssistantChatResponse response = promptService.buildResponse(
                    conversationId,
                    reply,
                    new AssistantTaskState(decision.route().taskType(), "completed"),
                    agent.nextActions()
            );
            conversationSessionService.appendConversation(currentUser.getId(), conversationId, userMessage, response.getReply());
            return response;
        } catch (Exception e) {
            log.error("Multi-agent assistant chat failed, conversationId={}, userId={}, route={}",
                    conversationId, currentUser.getId(), decision.route(), e);
            return promptService.buildResponse(
                    conversationId,
                    "AI 助手处理失败，请稍后再试。",
                    new AssistantTaskState(decision.route().taskType(), "failed"),
                    agent.nextActions()
            );
        }
    }

    @Override
    public SseEmitter streamChat(AssistantChatRequest request, LoginUser currentUser) {
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        String userMessage = request.getMessage().trim();
        List<AssistantConversationMessage> history = conversationSessionService.getMessages(currentUser.getId(), conversationId);
        String longTermMemoryPrompt = prepareLongTermMemory(currentUser.getId(), userMessage);
        AssistantSupervisorDecision decision = supervisorAgent.decide(currentUser, history, userMessage);
        AbstractAssistantAgent agent = resolveAgent(decision);

        SseEmitter emitter = new SseEmitter(assistantProperties.getStreamTimeout().toMillis());
        StringBuilder assistantReply = new StringBuilder();
        AtomicBoolean emitterClosed = new AtomicBoolean(false);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        AssistantToolEventEmitter toolEventEmitter = (eventName, toolName, message) ->
                sendEvent(emitter, eventName, AssistantStreamPayload.builder()
                        .conversationId(conversationId)
                        .toolName(toolName)
                        .message(message)
                        .build(), emitterClosed);

        sendEvent(emitter, "start", AssistantStreamPayload.builder()
                .conversationId(conversationId)
                .message("已分配给" + decision.route().displayName() + "，开始处理")
                .build(), emitterClosed);

        Flux<String> contentFlux;
        try {
            contentFlux = agent.stream(
                    currentUser,
                    history,
                    userMessage,
                    decision,
                    promptService,
                    longTermMemoryPrompt,
                    buildToolContext(currentUser, conversationId, toolEventEmitter)
            );
        } catch (Exception e) {
            log.error("Multi-agent stream init failed, conversationId={}, userId={}, route={}",
                    conversationId, currentUser.getId(), decision.route(), e);
            sendEvent(emitter, "error", AssistantStreamPayload.builder()
                    .conversationId(conversationId)
                    .message("AI 流式会话初始化失败，请检查模型配置。")
                    .build(), emitterClosed);
            safeComplete(emitter, emitterClosed);
            return emitter;
        }

        Disposable subscription = contentFlux.subscribe(
                chunk -> {
                    assistantReply.append(chunk);
                    boolean delivered = sendEvent(emitter, "delta", AssistantStreamPayload.builder()
                            .conversationId(conversationId)
                            .content(chunk)
                            .build(), emitterClosed);
                    if (!delivered) {
                        disposeSubscription(subscriptionRef);
                    }
                },
                error -> {
                    log.error("Multi-agent streaming failed, conversationId={}, userId={}, route={}",
                            conversationId, currentUser.getId(), decision.route(), error);
                    sendEvent(emitter, "error", AssistantStreamPayload.builder()
                            .conversationId(conversationId)
                            .message("AI 流式会话执行失败，请稍后再试。")
                            .build(), emitterClosed);
                    safeComplete(emitter, emitterClosed);
                },
                () -> {
                    AssistantChatResponse response = promptService.buildResponse(
                            conversationId,
                            assistantReply.toString(),
                            new AssistantTaskState(decision.route().taskType(), "completed"),
                            agent.nextActions()
                    );
                    conversationSessionService.appendConversation(currentUser.getId(), conversationId, userMessage, response.getReply());
                    sendEvent(emitter, "complete", AssistantStreamPayload.builder()
                            .conversationId(response.getConversationId())
                            .reply(response.getReply())
                            .paragraphs(response.getParagraphs())
                            .taskState(response.getTaskState())
                            .nextActions(response.getNextActions())
                            .build(), emitterClosed);
                    safeComplete(emitter, emitterClosed);
                }
        );
        subscriptionRef.set(subscription);

        emitter.onTimeout(() -> {
            log.warn("Multi-agent streaming timed out, conversationId={}, userId={}, route={}",
                    conversationId, currentUser.getId(), decision.route());
            disposeSubscription(subscriptionRef);
            safeComplete(emitter, emitterClosed);
        });
        emitter.onError(error -> {
            log.warn("Multi-agent emitter error, conversationId={}, userId={}, route={}",
                    conversationId, currentUser.getId(), decision.route(), error);
            disposeSubscription(subscriptionRef);
            emitterClosed.set(true);
        });
        emitter.onCompletion(() -> {
            disposeSubscription(subscriptionRef);
            emitterClosed.set(true);
        });
        return emitter;
    }

    private AbstractAssistantAgent resolveAgent(AssistantSupervisorDecision decision) {
        return agents.getOrDefault(decision.route(), agents.get(AssistantAgentRoute.SEARCH_QA));
    }

    private Map<String, Object> buildToolContext(LoginUser currentUser,
                                                 String conversationId,
                                                 AssistantToolEventEmitter toolEventEmitter) {
        Map<String, Object> context = new HashMap<>();
        context.put(AssistantToolContextSupport.CURRENT_USER_ID, currentUser.getId());
        context.put(AssistantToolContextSupport.CONVERSATION_ID, conversationId);
        context.put(AssistantToolContextSupport.TOOL_EVENT_EMITTER, toolEventEmitter);
        return context;
    }

    private boolean sendEvent(SseEmitter emitter, String eventName, Object payload, AtomicBoolean emitterClosed) {
        if (emitterClosed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            return true;
        } catch (IOException e) {
            emitterClosed.set(true);
            log.warn("Failed to send assistant SSE event, event={}", eventName, e);
            return false;
        }
    }

    private void safeComplete(SseEmitter emitter, AtomicBoolean emitterClosed) {
        if (emitterClosed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private void disposeSubscription(AtomicReference<Disposable> subscriptionRef) {
        Disposable subscription = subscriptionRef.getAndSet(null);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    private String prepareLongTermMemory(Long userId, String userMessage) {
        try {
            longTermMemoryService.rememberUserMessage(userId, userMessage);
            return longTermMemoryService.buildMemoryPrompt(userId);
        } catch (Exception e) {
            log.warn("Failed to load assistant long-term memory, userId={}", userId, e);
            return "";
        }
    }
}
