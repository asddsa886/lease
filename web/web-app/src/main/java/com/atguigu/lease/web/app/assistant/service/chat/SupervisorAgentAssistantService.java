package com.atguigu.lease.web.app.assistant.service.chat;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.config.AssistantProperties;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatRequest;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.dto.AssistantStreamPayload;
import com.atguigu.lease.web.app.assistant.dto.AssistantTaskState;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantExecutionAudit;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantRouteDecision;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantRoutingSupervisor;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSpecialist;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSpecialistRequest;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSpecialistResult;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSpecialistType;
import com.atguigu.lease.web.app.assistant.service.agent.AuditedToolEventEmitter;
import com.atguigu.lease.web.app.assistant.service.memory.RedisAssistantLongTermMemoryService;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import com.atguigu.lease.web.app.assistant.service.session.RedisAssistantConversationSessionService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SupervisorAgentAssistantService implements AppAssistantService {

    private final AssistantPromptService promptService;
    private final RedisAssistantConversationSessionService conversationSessionService;
    private final RedisAssistantLongTermMemoryService longTermMemoryService;
    private final AssistantProperties assistantProperties;
    private final AssistantRoutingSupervisor routingSupervisor;
    private final Map<AssistantSpecialistType, AssistantSpecialist> specialists;
    private final AppAssistantService fallbackAssistantService;

    public SupervisorAgentAssistantService(AssistantPromptService promptService,
                                           RedisAssistantConversationSessionService conversationSessionService,
                                           RedisAssistantLongTermMemoryService longTermMemoryService,
                                           AssistantProperties assistantProperties,
                                           AssistantRoutingSupervisor routingSupervisor,
                                           List<AssistantSpecialist> specialists,
                                           AppAssistantService fallbackAssistantService) {
        this.promptService = promptService;
        this.conversationSessionService = conversationSessionService;
        this.longTermMemoryService = longTermMemoryService;
        this.assistantProperties = assistantProperties;
        this.routingSupervisor = routingSupervisor;
        this.fallbackAssistantService = fallbackAssistantService;
        this.specialists = new LinkedHashMap<>();
        for (AssistantSpecialist specialist : specialists) {
            this.specialists.put(specialist.type(), specialist);
        }
    }

    @Override
    public AssistantChatResponse chat(AssistantChatRequest request, LoginUser currentUser) {
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        String userMessage = request.getMessage().trim();
        List<AssistantConversationMessage> history = conversationSessionService.getMessages(currentUser.getId(), conversationId);
        String longTermMemoryPrompt = prepareLongTermMemory(currentUser.getId(), userMessage);
        AssistantRouteDecision decision = routingSupervisor.route(userMessage);
        AuditedToolEventEmitter emitter = new AuditedToolEventEmitter(AssistantToolEventEmitter.noop());

        try {
            AssistantSpecialistResult result = runSpecialists(decision, currentUser, conversationId, userMessage, history, longTermMemoryPrompt, emitter);
            if (result.reply() == null || result.reply().isBlank()) {
                return fallbackWithAudit("empty-reply", conversationId, decision, emitter, request, currentUser);
            }

            AssistantChatResponse response = promptService.buildResponse(
                    conversationId,
                    result.reply(),
                    new AssistantTaskState(result.type().getTaskType(), "completed"),
                    result.nextActions()
            );
            conversationSessionService.appendConversation(currentUser.getId(), conversationId, userMessage, response.getReply());
            logAudit(new AssistantExecutionAudit(
                    conversationId,
                    decision.primary(),
                    decision.secondary(),
                    emitter.toolNames(),
                    emitter.toolNames().contains("searchKnowledge"),
                    false,
                    null
            ));
            return response;
        } catch (Exception e) {
            log.warn("Supervisor assistant failed, fallback to legacy assistant, conversationId={}, routeReason={}",
                    conversationId, decision.reason(), e);
            return fallbackWithAudit("exception", conversationId, decision, emitter, request, currentUser);
        }
    }

    @Override
    public SseEmitter streamChat(AssistantChatRequest request, LoginUser currentUser) {
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        String userMessage = request.getMessage().trim();
        List<AssistantConversationMessage> history = conversationSessionService.getMessages(currentUser.getId(), conversationId);
        String longTermMemoryPrompt = prepareLongTermMemory(currentUser.getId(), userMessage);
        AssistantRouteDecision decision = routingSupervisor.route(userMessage);

        SseEmitter emitter = new SseEmitter(assistantProperties.getStreamTimeout().toMillis());
        AtomicBoolean emitterClosed = new AtomicBoolean(false);
        AuditedToolEventEmitter auditedEmitter = new AuditedToolEventEmitter((eventName, toolName, message) ->
                sendEvent(emitter, eventName, AssistantStreamPayload.builder()
                        .conversationId(conversationId)
                        .toolName(toolName)
                        .message(message)
                        .build(), emitterClosed));

        sendEvent(emitter, "start", AssistantStreamPayload.builder()
                .conversationId(conversationId)
                .message("顾问助手已开始分析，本轮会先做意图路由，再调用相应专员。")
                .build(), emitterClosed);
        auditedEmitter.emit("tool_call", "supervisor-routing", "正在分析用户意图");
        auditedEmitter.emit("tool_result", "supervisor-routing",
                "已路由到 " + decision.primary().getTaskType() + (decision.hasSecondary() ? "，并补充 " + decision.secondary().getTaskType() : ""));

        try {
            AssistantSpecialistResult result = runSpecialists(
                    decision,
                    currentUser,
                    conversationId,
                    userMessage,
                    history,
                    longTermMemoryPrompt,
                    auditedEmitter
            );
            if (result.reply() == null || result.reply().isBlank()) {
                return fallbackStreamWithAudit("empty-reply", conversationId, decision, auditedEmitter, request, currentUser);
            }

            AssistantChatResponse response = promptService.buildResponse(
                    conversationId,
                    result.reply(),
                    new AssistantTaskState(result.type().getTaskType(), "completed"),
                    result.nextActions()
            );
            conversationSessionService.appendConversation(currentUser.getId(), conversationId, userMessage, response.getReply());
            for (String paragraph : response.getParagraphs()) {
                sendEvent(emitter, "delta", AssistantStreamPayload.builder()
                        .conversationId(conversationId)
                        .content(paragraph + "\n\n")
                        .build(), emitterClosed);
            }
            sendEvent(emitter, "complete", AssistantStreamPayload.builder()
                    .conversationId(response.getConversationId())
                    .reply(response.getReply())
                    .paragraphs(response.getParagraphs())
                    .taskState(response.getTaskState())
                    .nextActions(response.getNextActions())
                    .build(), emitterClosed);
            safeComplete(emitter, emitterClosed);
            logAudit(new AssistantExecutionAudit(
                    conversationId,
                    decision.primary(),
                    decision.secondary(),
                    auditedEmitter.toolNames(),
                    auditedEmitter.toolNames().contains("searchKnowledge"),
                    false,
                    null
            ));
            return emitter;
        } catch (Exception e) {
            log.warn("Supervisor assistant streaming failed, fallback to legacy assistant, conversationId={}, routeReason={}",
                    conversationId, decision.reason(), e);
            return fallbackStreamWithAudit("exception", conversationId, decision, auditedEmitter, request, currentUser);
        }
    }

    private AssistantSpecialistResult runSpecialists(AssistantRouteDecision decision,
                                                     LoginUser currentUser,
                                                     String conversationId,
                                                     String userMessage,
                                                     List<AssistantConversationMessage> history,
                                                     String longTermMemoryPrompt,
                                                     AssistantToolEventEmitter emitter) {
        AssistantSpecialist primary = requireSpecialist(decision.primary());
        AssistantSpecialistResult primaryResult = primary.handle(new AssistantSpecialistRequest(
                currentUser,
                conversationId,
                userMessage,
                history,
                longTermMemoryPrompt,
                emitter
        ));
        if (!decision.hasSecondary()) {
            return primaryResult;
        }

        AssistantSpecialist secondary = requireSpecialist(decision.secondary());
        String mergedMessage = userMessage + "\n\n补充上下文：\n" + primaryResult.reply();
        AssistantSpecialistResult secondaryResult = secondary.handle(new AssistantSpecialistRequest(
                currentUser,
                conversationId,
                mergedMessage,
                history,
                longTermMemoryPrompt,
                emitter
        ));

        String mergedReply = """
                %s

                补充说明：
                %s
                """.formatted(primaryResult.reply().trim(), secondaryResult.reply().trim());

        return new AssistantSpecialistResult(
                secondaryResult.type(),
                mergedReply,
                secondaryResult.nextActions().isEmpty() ? primaryResult.nextActions() : secondaryResult.nextActions()
        );
    }

    private AssistantSpecialist requireSpecialist(AssistantSpecialistType type) {
        AssistantSpecialist specialist = specialists.get(type);
        if (specialist == null) {
            throw new IllegalStateException("Missing assistant specialist for type " + type);
        }
        return specialist;
    }

    private AssistantChatResponse fallbackWithAudit(String reason,
                                                    String conversationId,
                                                    AssistantRouteDecision decision,
                                                    AuditedToolEventEmitter auditedEmitter,
                                                    AssistantChatRequest request,
                                                    LoginUser currentUser) {
        logAudit(new AssistantExecutionAudit(
                conversationId,
                decision.primary(),
                decision.secondary(),
                auditedEmitter.toolNames(),
                auditedEmitter.toolNames().contains("searchKnowledge"),
                true,
                reason
        ));
        return fallbackAssistantService.chat(request, currentUser);
    }

    private SseEmitter fallbackStreamWithAudit(String reason,
                                               String conversationId,
                                               AssistantRouteDecision decision,
                                               AuditedToolEventEmitter auditedEmitter,
                                               AssistantChatRequest request,
                                               LoginUser currentUser) {
        logAudit(new AssistantExecutionAudit(
                conversationId,
                decision.primary(),
                decision.secondary(),
                auditedEmitter.toolNames(),
                auditedEmitter.toolNames().contains("searchKnowledge"),
                true,
                reason
        ));
        return fallbackAssistantService.streamChat(request, currentUser);
    }

    private void logAudit(AssistantExecutionAudit audit) {
        log.info("assistant-route conversationId={} primary={} secondary={} tools={} ragUsed={} fallbackUsed={} fallbackReason={}",
                audit.conversationId(),
                audit.primary(),
                audit.secondary(),
                audit.toolsUsed(),
                audit.ragUsed(),
                audit.fallbackUsed(),
                audit.fallbackReason());
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

    private boolean sendEvent(SseEmitter emitter, String eventName, Object payload, AtomicBoolean emitterClosed) {
        if (emitterClosed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            return true;
        } catch (IOException e) {
            emitterClosed.set(true);
            log.warn("Failed to send supervisor assistant SSE event, event={}", eventName, e);
            return false;
        }
    }

    private void safeComplete(SseEmitter emitter, AtomicBoolean emitterClosed) {
        if (emitterClosed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }
}
