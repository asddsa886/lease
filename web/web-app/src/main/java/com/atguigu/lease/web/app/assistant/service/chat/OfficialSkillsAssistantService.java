package com.atguigu.lease.web.app.assistant.service.chat;

import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.config.AssistantProperties;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatRequest;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import com.atguigu.lease.web.app.assistant.dto.AssistantStreamPayload;
import com.atguigu.lease.web.app.assistant.dto.AssistantTaskState;
import com.atguigu.lease.web.app.assistant.service.memory.RedisAssistantLongTermMemoryService;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import com.atguigu.lease.web.app.assistant.service.session.RedisAssistantConversationSessionService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolContextSupport;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class OfficialSkillsAssistantService implements AppAssistantService {

    private static final String TASK_TYPE = "assistant";

    private final ChatClient chatClient;
    private final AssistantPromptService promptService;
    private final RedisAssistantConversationSessionService conversationSessionService;
    private final RedisAssistantLongTermMemoryService longTermMemoryService;
    private final AssistantProperties assistantProperties;

    public OfficialSkillsAssistantService(ChatModel chatModel,
                                          AssistantPromptService promptService,
                                          RedisAssistantConversationSessionService conversationSessionService,
                                          AssistantProperties assistantProperties,
                                          AssistantRoomTools roomTools,
                                          AssistantBrowsingHistoryTools browsingHistoryTools,
                                          AssistantAppointmentTools appointmentTools,
                                          AssistantLeaseOrderTools leaseOrderTools,
                                          AssistantKnowledgeTools knowledgeTools,
                                          RedisAssistantLongTermMemoryService longTermMemoryService,
                                          SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                          ToolCallback readSkillToolCallback) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(skillPromptAugmentAdvisor)
                .defaultTools(
                        roomTools,
                        browsingHistoryTools,
                        appointmentTools,
                        leaseOrderTools,
                        knowledgeTools
                )
                .defaultToolCallbacks(readSkillToolCallback)
                .build();
        this.promptService = promptService;
        this.conversationSessionService = conversationSessionService;
        this.longTermMemoryService = longTermMemoryService;
        this.assistantProperties = assistantProperties;
    }

    @Override
    public AssistantChatResponse chat(AssistantChatRequest request, LoginUser currentUser) {
        String userMessage = request.getMessage().trim();
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        List<AssistantConversationMessage> history = conversationSessionService.getMessages(currentUser.getId(), conversationId);
        String longTermMemoryPrompt = prepareLongTermMemory(currentUser.getId(), userMessage);
        List<Message> messages = promptService.buildPromptMessages(currentUser, history, userMessage, longTermMemoryPrompt);

        try {
            String reply = chatClient.prompt()
                    .messages(messages)
                    .toolContext(buildToolContext(currentUser, conversationId, AssistantToolEventEmitter.noop()))
                    .call()
                    .content();

            AssistantChatResponse response = promptService.buildResponse(
                    conversationId,
                    reply,
                    new AssistantTaskState(TASK_TYPE, "completed"),
                    null
            );
            conversationSessionService.appendConversation(currentUser.getId(), conversationId, userMessage, response.getReply());
            return response;
        } catch (Exception e) {
            log.error("Official skills assistant chat failed, conversationId={}, userId={}",
                    conversationId, currentUser.getId(), e);
            return promptService.buildResponse(
                    conversationId,
                    "AI 助手处理失败，请稍后再试。",
                    new AssistantTaskState(TASK_TYPE, "failed"),
                    null
            );
        }
    }

    @Override
    public SseEmitter streamChat(AssistantChatRequest request, LoginUser currentUser) {
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        String userMessage = request.getMessage().trim();
        List<AssistantConversationMessage> history = conversationSessionService.getMessages(currentUser.getId(), conversationId);
        String longTermMemoryPrompt = prepareLongTermMemory(currentUser.getId(), userMessage);
        List<Message> messages = promptService.buildPromptMessages(currentUser, history, userMessage, longTermMemoryPrompt);

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
                .message("助手已开始处理，本轮会按官方 Skills 机制选择规则与工具。")
                .build(), emitterClosed);

        Flux<String> contentFlux;
        try {
            contentFlux = chatClient.prompt()
                    .messages(messages)
                    .toolContext(buildToolContext(currentUser, conversationId, toolEventEmitter))
                    .stream()
                    .content();
        } catch (Exception e) {
            log.error("Official skills assistant stream init failed, conversationId={}, userId={}",
                    conversationId, currentUser.getId(), e);
            sendEvent(emitter, "error", AssistantStreamPayload.builder()
                    .conversationId(conversationId)
                    .message("AI 流式会话初始化失败，请检查模型与 Skills 配置。")
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
                    log.error("Official skills assistant streaming failed, conversationId={}, userId={}",
                            conversationId, currentUser.getId(), error);
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
                            new AssistantTaskState(TASK_TYPE, "completed"),
                            null
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
            log.warn("Official skills assistant streaming timed out, conversationId={}, userId={}",
                    conversationId, currentUser.getId());
            disposeSubscription(subscriptionRef);
            safeComplete(emitter, emitterClosed);
        });
        emitter.onError(error -> {
            log.warn("Official skills assistant emitter error, conversationId={}, userId={}",
                    conversationId, currentUser.getId(), error);
            disposeSubscription(subscriptionRef);
            emitterClosed.set(true);
        });
        emitter.onCompletion(() -> {
            disposeSubscription(subscriptionRef);
            emitterClosed.set(true);
        });
        return emitter;
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
