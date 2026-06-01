package com.atguigu.lease.web.app.assistant.service.chat;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.config.AssistantProperties;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatRequest;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import com.atguigu.lease.web.app.assistant.dto.AssistantStreamPayload;
import com.atguigu.lease.web.app.assistant.dto.AssistantTaskState;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantOrchestrationAudit;
import com.atguigu.lease.web.app.assistant.service.agent.AuditedToolEventEmitter;
import com.atguigu.lease.web.app.assistant.service.agent.SpecialistAgentType;
import com.atguigu.lease.web.app.assistant.service.agent.SupervisorAgent;
import com.atguigu.lease.web.app.assistant.service.agent.SupervisorAgentRequest;
import com.atguigu.lease.web.app.assistant.service.agent.SupervisorExecutionResult;
import com.atguigu.lease.web.app.assistant.service.agent.SupervisorPlanStep;
import com.atguigu.lease.web.app.assistant.service.memory.RedisAssistantLongTermMemoryService;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import com.atguigu.lease.web.app.assistant.service.session.RedisAssistantConversationSessionService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class SupervisorAgentAssistantService implements AppAssistantService {

    private final AssistantPromptService promptService;
    private final RedisAssistantConversationSessionService conversationSessionService;
    private final RedisAssistantLongTermMemoryService longTermMemoryService;
    private final AssistantProperties assistantProperties;
    private final SupervisorAgent supervisorAgent;
    private final AppAssistantService fallbackAssistantService;

    public SupervisorAgentAssistantService(AssistantPromptService promptService,
                                           RedisAssistantConversationSessionService conversationSessionService,
                                           RedisAssistantLongTermMemoryService longTermMemoryService,
                                           AssistantProperties assistantProperties,
                                           SupervisorAgent supervisorAgent,
                                           AppAssistantService fallbackAssistantService) {
        this.promptService = promptService;
        this.conversationSessionService = conversationSessionService;
        this.longTermMemoryService = longTermMemoryService;
        this.assistantProperties = assistantProperties;
        this.supervisorAgent = supervisorAgent;
        this.fallbackAssistantService = fallbackAssistantService;
    }

    @Override
    public AssistantChatResponse chat(AssistantChatRequest request, LoginUser currentUser) {
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        request.setConversationId(conversationId);
        String userMessage = request.getMessage().trim();
        List<AssistantConversationMessage> history = conversationSessionService.getMessages(currentUser.getId(), conversationId);
        String longTermMemoryPrompt = prepareLongTermMemory(currentUser.getId(), userMessage);
        AuditedToolEventEmitter auditedEmitter = new AuditedToolEventEmitter(AssistantToolEventEmitter.noop());

        try {
            SupervisorExecutionResult result = supervisorAgent.execute(new SupervisorAgentRequest(
                    currentUser,
                    conversationId,
                    userMessage,
                    history,
                    longTermMemoryPrompt,
                    auditedEmitter
            ));
            AssistantChatResponse response = buildResponse(conversationId, result);
            conversationSessionService.appendConversation(currentUser.getId(), conversationId, userMessage, response.getReply());
            logAudit(new AssistantOrchestrationAudit(
                    conversationId,
                    result.plan(),
                    result.executedSteps().stream().map(SupervisorPlanStep::agentType).toList(),
                    auditedEmitter.toolNames(),
                    auditedEmitter.toolNames().contains("searchKnowledge"),
                    result.needsClarification(),
                    false,
                    null
            ));
            return response;
        } catch (Exception e) {
            log.warn("Supervisor multi-agent assistant failed, fallback to legacy assistant, conversationId={}",
                    conversationId, e);
            return fallbackWithAudit("exception", conversationId, auditedEmitter, request, currentUser);
        }
    }

    @Override
    public SseEmitter streamChat(AssistantChatRequest request, LoginUser currentUser) {
        String conversationId = conversationSessionService.resolveConversationId(currentUser.getId(), request.getConversationId());
        request.setConversationId(conversationId);
        String userMessage = request.getMessage().trim();
        List<AssistantConversationMessage> history = conversationSessionService.getMessages(currentUser.getId(), conversationId);
        String longTermMemoryPrompt = prepareLongTermMemory(currentUser.getId(), userMessage);

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
                .message("多 Agent 助手已开始处理，本轮将先由 Supervisor 规划，再调度 Specialist Agents。")
                .build(), emitterClosed);

        try {
            SupervisorExecutionResult result = supervisorAgent.execute(new SupervisorAgentRequest(
                    currentUser,
                    conversationId,
                    userMessage,
                    history,
                    longTermMemoryPrompt,
                    auditedEmitter
            ));
            AssistantChatResponse response = buildResponse(conversationId, result);
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
            logAudit(new AssistantOrchestrationAudit(
                    conversationId,
                    result.plan(),
                    result.executedSteps().stream().map(SupervisorPlanStep::agentType).toList(),
                    auditedEmitter.toolNames(),
                    auditedEmitter.toolNames().contains("searchKnowledge"),
                    result.needsClarification(),
                    false,
                    null
            ));
            return emitter;
        } catch (Exception e) {
            log.warn("Supervisor multi-agent streaming failed, fallback to legacy assistant, conversationId={}",
                    conversationId, e);
            return fallbackStreamWithAudit("exception", conversationId, auditedEmitter, request, currentUser);
        }
    }

    private AssistantChatResponse buildResponse(String conversationId, SupervisorExecutionResult result) {
        SpecialistAgentType primaryType = result.primaryAgentType() == null
                ? SpecialistAgentType.CUSTOMER_SUPPORT
                : result.primaryAgentType();
        String taskStatus = result.needsClarification() ? "clarification-required" : "completed";
        return promptService.buildResponse(
                conversationId,
                result.reply(),
                new AssistantTaskState(primaryType.getTaskType(), taskStatus),
                result.nextActions()
        );
    }

    private AssistantChatResponse fallbackWithAudit(String reason,
                                                    String conversationId,
                                                    AuditedToolEventEmitter auditedEmitter,
                                                    AssistantChatRequest request,
                                                    LoginUser currentUser) {
        logAudit(new AssistantOrchestrationAudit(
                conversationId,
                null,
                List.of(),
                auditedEmitter.toolNames(),
                auditedEmitter.toolNames().contains("searchKnowledge"),
                false,
                true,
                reason
        ));
        return fallbackAssistantService.chat(request, currentUser);
    }

    private SseEmitter fallbackStreamWithAudit(String reason,
                                               String conversationId,
                                               AuditedToolEventEmitter auditedEmitter,
                                               AssistantChatRequest request,
                                               LoginUser currentUser) {
        logAudit(new AssistantOrchestrationAudit(
                conversationId,
                null,
                List.of(),
                auditedEmitter.toolNames(),
                auditedEmitter.toolNames().contains("searchKnowledge"),
                false,
                true,
                reason
        ));
        return fallbackAssistantService.streamChat(request, currentUser);
    }

    private void logAudit(AssistantOrchestrationAudit audit) {
        log.info("assistant-orchestration conversationId={} plan={} executedAgents={} tools={} ragUsed={} clarificationUsed={} fallbackUsed={} fallbackReason={}",
                audit.conversationId(),
                audit.plan(),
                audit.executedAgents(),
                audit.toolsUsed(),
                audit.ragUsed(),
                audit.clarificationUsed(),
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
            log.warn("Failed to send multi-agent assistant SSE event, event={}", eventName, e);
            return false;
        }
    }

    private void safeComplete(SseEmitter emitter, AtomicBoolean emitterClosed) {
        if (emitterClosed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }
}
