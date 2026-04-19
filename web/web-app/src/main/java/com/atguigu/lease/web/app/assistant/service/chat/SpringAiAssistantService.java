package com.atguigu.lease.web.app.assistant.service.chat;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.config.AssistantProperties;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatRequest;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import com.atguigu.lease.web.app.assistant.dto.AssistantStreamPayload;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationSessionService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolContextSupport;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
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
public class SpringAiAssistantService implements AppAssistantService {

    private final ChatClient chatClient;
    private final AssistantPromptService promptService;
    private final AssistantConversationSessionService conversationSessionService;
    private final AssistantProperties assistantProperties;
    private final AssistantApartmentTools apartmentTools;
    private final AssistantRoomTools roomTools;
    private final AssistantBrowsingHistoryTools browsingHistoryTools;
    private final AssistantAppointmentTools appointmentTools;
    private final AssistantLeaseOrderTools leaseOrderTools;

    public SpringAiAssistantService(ChatModel chatModel,
                                    AssistantPromptService promptService,
                                    AssistantConversationSessionService conversationSessionService,
                                    AssistantProperties assistantProperties,
                                    AssistantApartmentTools apartmentTools,
                                    AssistantRoomTools roomTools,
                                    AssistantBrowsingHistoryTools browsingHistoryTools,
                                    AssistantAppointmentTools appointmentTools,
                                    AssistantLeaseOrderTools leaseOrderTools) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.promptService = promptService;
        this.conversationSessionService = conversationSessionService;
        this.assistantProperties = assistantProperties;
        this.apartmentTools = apartmentTools;
        this.roomTools = roomTools;
        this.browsingHistoryTools = browsingHistoryTools;
        this.appointmentTools = appointmentTools;
        this.leaseOrderTools = leaseOrderTools;
    }

    @Override
    public AssistantChatResponse chat(AssistantChatRequest request, LoginUser currentUser) {
        String userMessage = request.getMessage().trim();
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        List<Message> messages = promptService.buildPromptMessages(
                currentUser,
                conversationSessionService.getMessages(currentUser.getId(), conversationId),
                userMessage
        );

        try {
            String reply = chatClient.prompt()
                    .messages(messages)
                    .tools(apartmentTools, roomTools, browsingHistoryTools, appointmentTools, leaseOrderTools)
                    .toolContext(buildToolContext(currentUser, conversationId, AssistantToolEventEmitter.noop()))
                    .call()
                    .content();

            AssistantChatResponse response = promptService.buildResponse(conversationId, reply);
            conversationSessionService.appendConversation(currentUser.getId(), conversationId, userMessage, response.getReply());
            return response;
        } catch (Exception e) {
            log.error("Assistant chat call failed, conversationId={}, userId={}", conversationId, currentUser.getId(), e);
            return promptService.buildResponse(conversationId, "AI 服务调用失败，请稍后再试，或检查模型配置是否正确。");
        }
    }

    @Override
    public SseEmitter streamChat(AssistantChatRequest request, LoginUser currentUser) {
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        String userMessage = request.getMessage().trim();
        List<Message> messages = promptService.buildPromptMessages(
                currentUser,
                conversationSessionService.getMessages(currentUser.getId(), conversationId),
                userMessage
        );

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
                .message("开始生成回复")
                .build(), emitterClosed);

        Flux<String> contentFlux;
        try {
            contentFlux = chatClient.prompt()
                    .messages(messages)
                    .tools(apartmentTools, roomTools, browsingHistoryTools, appointmentTools, leaseOrderTools)
                    .toolContext(buildToolContext(currentUser, conversationId, toolEventEmitter))
                    .stream()
                    .content();
        } catch (Exception e) {
            log.error("Assistant stream init failed, conversationId={}, userId={}", conversationId, currentUser.getId(), e);
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
                    log.error("Assistant streaming failed, conversationId={}, userId={}", conversationId, currentUser.getId(), error);
                    sendEvent(emitter, "error", AssistantStreamPayload.builder()
                            .conversationId(conversationId)
                            .message("AI 流式会话执行失败，请稍后再试。")
                            .build(), emitterClosed);
                    safeComplete(emitter, emitterClosed);
                },
                () -> {
                    AssistantChatResponse response = promptService.buildResponse(conversationId, assistantReply.toString());
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
            log.warn("Assistant streaming timed out, conversationId={}, userId={}", conversationId, currentUser.getId());
            disposeSubscription(subscriptionRef);
            safeComplete(emitter, emitterClosed);
        });
        emitter.onError(error -> {
            log.warn("Assistant streaming emitter error, conversationId={}, userId={}", conversationId, currentUser.getId(), error);
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
}
