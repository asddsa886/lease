package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.chat.agent.AppointmentTimeParser;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskState;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskStateStore;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.dto.AssistantToolExecutionVo;
import com.atguigu.lease.web.app.chat.memory.AssistantMongoChatMemoryStore;
import com.atguigu.lease.web.app.chat.tool.RentalAssistantTools;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatService {
    private static final String DEFAULT_EMPTY_REPLY = "我当前没有生成有效回复，请换个问法再试。";
    private static final int STREAM_CHUNK_SIZE = 24;
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final Pattern APPOINTMENT_ID_PATTERN = Pattern.compile("(?:预约(?:ID|id)?\\s*|ID\\s*|id\\s*)(\\d+)");
    private static final List<String> TOOL_HEAVY_HINTS = List.of(
            "房源", "房间", "房号", "公寓", "小区", "预约", "租约", "租房",
            "月租", "租金", "朝阳", "海淀", "昌平", "通州", "北京市", "押一付三",
            "流程", "退租", "续约", "详情", "介绍", "第二个", "这个", "那个"
    );

    private final AssistantProperties assistantProperties;
    private final ObjectProvider<RentalAssistant> rentalAssistantProvider;
    private final ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;
    private final ObjectProvider<AssistantMongoChatMemoryStore> assistantChatMemoryStoreProvider;
    private final RentalAssistantTools rentalAssistantTools;
    private final AssistantTaskStateStore assistantTaskStateStore;
    private final AssistantConversationSupport conversationSupport;

    public AssistantChatResponseVo chat(String message) {
        return chat(message, null);
    }

    public AssistantChatResponseVo chat(String message, String conversationId) {
        String question = normalizeQuestion(message);
        String resolvedConversationId = resolveConversationId(conversationId);
        ensureAssistantEnabled();
        AssistantChatResponseVo localAgentResponse = handleAgentAction(question, resolvedConversationId);
        if (localAgentResponse != null) {
            logAssistantSuccess(question, localAgentResponse);
            return localAgentResponse;
        }
        RentalAssistant rentalAssistant = requireAssistant();

        try {
            Result<String> assistantResult = invokeAssistantWithRecovery(rentalAssistant, resolvedConversationId, question);
            AssistantChatResponseVo response = conversationSupport.buildAssistantResponse(resolvedConversationId, assistantResult);
            logAssistantSuccess(question, response);
            return response;
        } catch (LeaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Assistant chat failed, conversationId={}", resolvedConversationId, e);
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), classifyErrorMessage(e));
        }
    }

    public SseEmitter stream(String message) {
        return stream(message, null);
    }

    public SseEmitter stream(String message, String conversationId) {
        SseEmitter emitter = new SseEmitter(resolveStreamTimeoutMillis());
        String question;
        String resolvedConversationId;
        var currentLoginUser = LoginUserHolder.get();
        Authentication currentAuthentication = SecurityContextHolder.getContext().getAuthentication();

        try {
            question = normalizeQuestion(message);
            resolvedConversationId = resolveConversationId(conversationId);
            ensureAssistantEnabled();
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
            return emitter;
        }

        AssistantChatResponseVo localAgentResponse = handleAgentAction(question, resolvedConversationId);
        if (localAgentResponse != null) {
            sendEvent(emitter, "start", Map.of(
                    "message", "开始生成回复",
                    "conversationId", resolvedConversationId,
                    "streamMode", "local-agent"
            ));
            runAsyncWithLoginContext(currentLoginUser, currentAuthentication, () -> {
                try {
                    emitSyntheticResponse(emitter, question, localAgentResponse);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    emitErrorAndComplete(emitter, e, resolvedConversationId);
                }
            });
            return emitter;
        }
        boolean nativeStreaming = shouldUseNativeStreaming(question);
        sendEvent(emitter, "start", Map.of(
                "message", "开始生成回复",
                "conversationId", resolvedConversationId,
                "streamMode", nativeStreaming ? "native" : "synthetic"
        ));

        if (nativeStreaming) {
            StreamingRentalAssistant streamingAssistant = streamingRentalAssistantProvider.getIfAvailable();
            runAsyncWithLoginContext(currentLoginUser, currentAuthentication, () -> startNativeStream(
                    emitter,
                    resolvedConversationId,
                    question,
                    streamingAssistant
            ));
        } else {
            RentalAssistant rentalAssistant = requireAssistant();
            runAsyncWithLoginContext(currentLoginUser, currentAuthentication, () -> startSyntheticStream(
                    emitter,
                    resolvedConversationId,
                    question,
                    rentalAssistant
            ));
        }
        return emitter;
    }


    private void ensureAssistantEnabled() {
        if (!assistantProperties.isEnabled()) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手未启用");
        }
    }

    private RentalAssistant requireAssistant() {
        ensureAssistantEnabled();

        RentalAssistant rentalAssistant = rentalAssistantProvider.getIfAvailable();
        if (rentalAssistant == null) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手尚未完成配置");
        }
        return rentalAssistant;
    }

    private void startSyntheticStream(SseEmitter emitter,
                                      String conversationId,
                                      String question,
                                      RentalAssistant rentalAssistant) {
        try {
            Result<String> assistantResult = invokeAssistantWithRecovery(rentalAssistant, conversationId, question);
            AssistantChatResponseVo response = conversationSupport.buildAssistantResponse(conversationId, assistantResult);
            emitSyntheticResponse(emitter, question, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitErrorAndComplete(emitter, e, conversationId);
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
        }
    }

    private void emitSyntheticResponse(SseEmitter emitter,
                                       String question,
                                       AssistantChatResponseVo response) throws InterruptedException {
        emitToolEvents(emitter, response);
        for (String chunk : splitForStreaming(response.getReply())) {
            if (!StringUtils.hasText(chunk)) {
                continue;
            }
            sendEvent(emitter, "delta", Map.of(
                    "content", chunk,
                    "conversationId", response.getConversationId()
            ));
            TimeUnit.MILLISECONDS.sleep(18);
        }

        sendEvent(emitter, "complete", conversationSupport.buildCompletePayload(response));
        emitter.complete();
        logAssistantSuccess(question, response);
    }

    private void startNativeStream(SseEmitter emitter,
                                   String conversationId,
                                   String question,
                                   StreamingRentalAssistant streamingRentalAssistant) {
        if (streamingRentalAssistant == null) {
            emitErrorAndComplete(
                    emitter,
                    new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手流式能力尚未完成配置"),
                    conversationId
            );
            return;
        }

        try {
            TokenStream tokenStream = streamingRentalAssistant.chat(conversationId, question);
            startTokenStream(emitter, conversationId, question, tokenStream);
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
        }
    }

    private void startTokenStream(SseEmitter emitter,
                                  String conversationId,
                                  String question,
                                  TokenStream tokenStream) {
        StringBuilder contentBuilder = new StringBuilder();
        List<AssistantToolExecutionVo> toolExecutions = new CopyOnWriteArrayList<>();
        List<ToolExecution> rawToolExecutions = new CopyOnWriteArrayList<>();

        tokenStream
                .onPartialResponse(token -> {
                    if (!StringUtils.hasText(token)) {
                        return;
                    }
                    contentBuilder.append(token);
                    sendEvent(emitter, "delta", Map.of(
                            "content", token,
                            "conversationId", conversationId
                    ));
                })
                .beforeToolExecution(beforeToolExecution -> {
                    sendEvent(emitter, "tool_call", Map.of(
                            "conversationId", conversationId,
                            "toolName", beforeToolExecution == null || beforeToolExecution.request() == null
                                    ? ""
                                    : blankToEmpty(beforeToolExecution.request().name()),
                            "toolArguments", beforeToolExecution == null || beforeToolExecution.request() == null
                                    ? ""
                                    : blankToEmpty(beforeToolExecution.request().arguments())
                    ));
                })
                .onToolExecuted(toolExecution -> {
                    rawToolExecutions.add(toolExecution);
                    AssistantToolExecutionVo vo = toToolExecutionVo(toolExecution);
                    toolExecutions.add(vo);
                    sendEvent(emitter, "tool_result", Map.of(
                            "conversationId", conversationId,
                            "toolName", blankToEmpty(vo.getToolName()),
                            "toolArguments", blankToEmpty(vo.getToolArguments()),
                            "toolResult", blankToEmpty(vo.getResultSummary()),
                            "failed", Boolean.TRUE.equals(vo.getFailed())
                    ));
                })
                .onCompleteResponse(chatResponse -> completeNativeStream(
                        emitter,
                        conversationId,
                        question,
                        contentBuilder,
                        toolExecutions,
                        rawToolExecutions,
                        chatResponse
                ))
                .onError(error -> emitErrorAndComplete(emitter, error, conversationId));

        tokenStream.start();
    }

    private void completeNativeStream(SseEmitter emitter,
                                      String conversationId,
                                      String question,
                                      StringBuilder contentBuilder,
                                      List<AssistantToolExecutionVo> toolExecutions,
                                      List<ToolExecution> rawToolExecutions,
                                      ChatResponse chatResponse) {
        AiMessage aiMessage = chatResponse == null ? null : chatResponse.aiMessage();
        String reply = aiMessage == null ? null : aiMessage.text();
        if (!StringUtils.hasText(reply)) {
            reply = contentBuilder.toString();
        }

        String formattedReply = conversationSupport.formatReply(reply);
        AssistantTaskState taskState = conversationSupport.resolveTaskState(conversationId, rawToolExecutions, false);
        AssistantChatResponseVo response = new AssistantChatResponseVo(
                conversationId,
                formattedReply,
                conversationSupport.splitParagraphs(formattedReply),
                conversationSupport.resolveAnswerSource(toolExecutions, List.of()),
                chatResponse == null || chatResponse.finishReason() == null
                        ? "unknown"
                        : chatResponse.finishReason().name().toLowerCase(Locale.ROOT),
                List.copyOf(toolExecutions),
                List.of(),
                conversationSupport.toTaskStateVo(taskState),
                conversationSupport.buildNextActions(taskState)
        );
        sendEvent(emitter, "complete", conversationSupport.buildCompletePayload(response));
        emitter.complete();
        logAssistantSuccess(question, response);
    }

    private AssistantChatResponseVo handleAgentAction(String question, String conversationId) {
        AssistantTaskState currentState = assistantTaskStateStore.get(conversationId);
        if (!StringUtils.hasText(question)) {
            log.debug("Assistant agent skipped, conversationId={}, hasState={}, questionPresent={}",
                    conversationId,
                    currentState != null,
                    StringUtils.hasText(question));
            return null;
        }

        currentState = bootstrapAppointmentTaskStateIfNeeded(conversationId, question, currentState);
        if (currentState == null) {
            log.debug("Assistant agent skipped, conversationId={}, hasState=false, questionPresent=true", conversationId);
            return null;
        }

        boolean loggedIn = isLoggedIn();
        AppointmentTimeParser.ParsedAppointmentTime parsedAppointmentTime = AppointmentTimeParser.parse(question, ZONE_ID);
        boolean confirmationFlow = "APPOINTMENT_CONFIRMING".equals(currentState.taskType());
        boolean cancelConfirmationFlow = "APPOINTMENT_CANCEL_CONFIRMING".equals(currentState.taskType());
        boolean rescheduleConfirmationFlow = "APPOINTMENT_RESCHEDULE_CONFIRMING".equals(currentState.taskType());
        boolean availabilityQuestion = isAppointmentAvailabilityQuestion(question, currentState);
        boolean appointmentIntent = isRoomAppointmentIntent(question, currentState)
                || ("APPOINTMENT_INTENT".equals(currentState.taskType()) && parsedAppointmentTime != null);
        boolean appointmentCancelIntent = isAppointmentCancelIntent(question, currentState);
        boolean appointmentRescheduleIntent = isAppointmentRescheduleIntent(question, currentState)
                || ("APPOINTMENT_RESCHEDULE_INTENT".equals(currentState.taskType()) && parsedAppointmentTime != null);
        log.info(
                "Assistant agent decision, conversationId={}, taskType={}, taskStatus={}, loggedIn={}, confirmationFlow={}, cancelConfirmationFlow={}, rescheduleConfirmationFlow={}, availabilityQuestion={}, appointmentIntent={}, appointmentCancelIntent={}, appointmentRescheduleIntent={}, question={}",
                conversationId,
                currentState.taskType(),
                currentState.taskStatus(),
                loggedIn,
                confirmationFlow,
                cancelConfirmationFlow,
                rescheduleConfirmationFlow,
                availabilityQuestion,
                appointmentIntent,
                appointmentCancelIntent,
                appointmentRescheduleIntent,
                question
        );

        if (confirmationFlow) {
            AssistantChatResponseVo confirmationResponse = handleAppointmentConfirmation(question, conversationId, currentState);
            if (confirmationResponse != null) {
                return confirmationResponse;
            }
        }

        if (cancelConfirmationFlow) {
            AssistantChatResponseVo cancelConfirmationResponse = handleAppointmentCancelConfirmation(question, conversationId, currentState);
            if (cancelConfirmationResponse != null) {
                return cancelConfirmationResponse;
            }
        }

        if (rescheduleConfirmationFlow) {
            AssistantChatResponseVo rescheduleConfirmationResponse = handleAppointmentRescheduleConfirmation(question, conversationId, currentState);
            if (rescheduleConfirmationResponse != null) {
                return rescheduleConfirmationResponse;
            }
        }

        if (availabilityQuestion) {
            return buildAppointmentIntentResponse(conversationId, currentState, loggedIn
                    ? "这个房源可以预约。告诉我你希望预约的时间，例如“明天下午”或“2026-04-10 15:00”，我就继续帮你安排。"
                    : "这个房源可以预约，但你还没有登录。请先登录，登录后告诉我预约时间，我就继续帮你安排。");
        }

        if (appointmentIntent) {
            return handleAppointmentIntent(question, conversationId, currentState, parsedAppointmentTime);
        }

        if (appointmentCancelIntent) {
            return handleAppointmentCancelIntent(question, conversationId, currentState);
        }

        if (appointmentRescheduleIntent) {
            return handleAppointmentRescheduleIntent(question, conversationId, currentState, parsedAppointmentTime);
        }

        return null;
    }

    private AssistantTaskState bootstrapAppointmentTaskStateIfNeeded(String conversationId,
                                                                     String question,
                                                                     AssistantTaskState currentState) {
        if (!isLoggedIn() || !looksLikeAppointmentManagementQuestion(question)) {
            return currentState;
        }
        if (currentState != null
                && currentState.selectedAppointmentId() != null
                && List.of(
                "APPOINTMENT_QUERY",
                "APPOINTMENT_CREATED",
                "APPOINTMENT_CANCELED",
                "APPOINTMENT_RESCHEDULED",
                "APPOINTMENT_CANCEL_CONFIRMING",
                "APPOINTMENT_RESCHEDULE_INTENT",
                "APPOINTMENT_RESCHEDULE_CONFIRMING"
        ).contains(currentState.taskType())) {
            return currentState;
        }

        AppointmentQueryContext appointmentQueryContext = loadAppointmentQueryContext();
        if (appointmentQueryContext.waitingAppointments().isEmpty()) {
            return currentState;
        }

        AppointmentSelection selectedAppointment = null;
        if (appointmentQueryContext.waitingAppointments().size() == 1
                || mentionsLatestAppointment(question)
                || (currentState != null && List.of(
                "ROOM_DETAIL",
                "APPOINTMENT_CREATED",
                "APPOINTMENT_CANCELED",
                "APPOINTMENT_RESCHEDULED"
        ).contains(currentState.taskType()))) {
            selectedAppointment = appointmentQueryContext.latestWaiting();
        }

        AssistantTaskState bootstrappedState = new AssistantTaskState(
                "APPOINTMENT_QUERY",
                "COMPLETED",
                currentState == null ? null : currentState.selectedRoomId(),
                currentState == null ? null : currentState.selectedRoomTitle(),
                currentState == null ? null : currentState.selectedApartmentId(),
                selectedAppointment == null ? null : selectedAppointment.appointmentId(),
                selectedAppointment == null ? null : selectedAppointment.label(),
                null,
                currentState == null ? List.of() : conversationSupport.safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, bootstrappedState);
        log.info(
                "Assistant appointment context bootstrapped, conversationId={}, taskType={}, selectedAppointmentId={}, waitingCount={}, question={}",
                conversationId,
                bootstrappedState.taskType(),
                bootstrappedState.selectedAppointmentId(),
                appointmentQueryContext.waitingAppointments().size(),
                question
        );
        return bootstrappedState;
    }

    private AssistantChatResponseVo handleAppointmentIntent(String question,
                                                            String conversationId,
                                                            AssistantTaskState currentState,
                                                            AppointmentTimeParser.ParsedAppointmentTime parsedAppointmentTime) {
        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_INTENT",
                    "NEEDS_LOGIN",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    null,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能直接创建预约。请先登录，登录后告诉我预约时间，我会继续帮你安排看房。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        if (parsedAppointmentTime == null) {
            return buildAppointmentIntentResponse(
                    conversationId,
                    currentState,
                    "我可以继续帮你安排这个房源的预约。请告诉我具体时间，例如“明天下午”“后天上午10点”或“2026-04-10 15:00”。"
            );
        }
        return buildAppointmentConfirmResponse(conversationId, currentState, parsedAppointmentTime.displayText());
    }

    private AssistantChatResponseVo handleAppointmentConfirmation(String question,
                                                                  String conversationId,
                                                                  AssistantTaskState currentState) {
        if (isNegativeConfirmation(question)) {
            AssistantTaskState revertedState = new AssistantTaskState(
                    "ROOM_DETAIL",
                    "WAITING_USER_INPUT",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    null,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, revertedState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "好的，已经取消这次预约操作。如果你想换个时间，可以直接告诉我新的预约时间。",
                    "agent",
                    List.of(),
                    List.of(),
                    revertedState
            );
        }

        AppointmentTimeParser.ParsedAppointmentTime updatedAppointmentTime = AppointmentTimeParser.parse(question, ZONE_ID);
        if (updatedAppointmentTime != null) {
            return buildAppointmentConfirmResponse(conversationId, currentState, updatedAppointmentTime.displayText());
        }

        if (!isPositiveConfirmation(question)) {
            return null;
        }

        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_INTENT",
                    "NEEDS_LOGIN",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    null,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能创建预约。请先登录，登录后我会继续帮你处理。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        return createAppointmentFromState(conversationId, currentState);
    }

    private AssistantChatResponseVo handleAppointmentCancelIntent(String question,
                                                                  String conversationId,
                                                                  AssistantTaskState currentState) {
        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_QUERY",
                    "NEEDS_LOGIN",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    currentState.selectedAppointmentId(),
                    currentState.selectedAppointmentLabel(),
                    currentState.proposedAppointmentTime(),
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能取消预约。请先登录后再试。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        AppointmentSelection appointmentSelection = resolveAppointmentSelection(question, currentState);
        if (appointmentSelection == null || appointmentSelection.appointmentId() == null) {
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "我还没确定你想取消哪一条预约。你可以直接说“取消预约13”或“取消最新预约”。",
                    "agent",
                    List.of(),
                    List.of(),
                    currentState
            );
        }

        return buildAppointmentCancelConfirmResponse(conversationId, currentState, appointmentSelection);
    }

    private AssistantChatResponseVo handleAppointmentCancelConfirmation(String question,
                                                                        String conversationId,
                                                                        AssistantTaskState currentState) {
        if (isNegativeConfirmation(question)) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_QUERY",
                    "COMPLETED",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    currentState.selectedAppointmentId(),
                    currentState.selectedAppointmentLabel(),
                    null,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "好的，这次我先不取消预约。如果你想继续处理其他预约，直接告诉我即可。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        if (!isPositiveConfirmation(question)) {
            return null;
        }

        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_QUERY",
                    "NEEDS_LOGIN",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    currentState.selectedAppointmentId(),
                    currentState.selectedAppointmentLabel(),
                    null,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能取消预约。请先登录后再试。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        return cancelAppointmentFromState(conversationId, currentState);
    }

    private AssistantChatResponseVo handleAppointmentRescheduleIntent(String question,
                                                                      String conversationId,
                                                                      AssistantTaskState currentState,
                                                                      AppointmentTimeParser.ParsedAppointmentTime parsedAppointmentTime) {
        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_QUERY",
                    "NEEDS_LOGIN",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    currentState.selectedAppointmentId(),
                    currentState.selectedAppointmentLabel(),
                    currentState.proposedAppointmentTime(),
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能改约。请先登录后再试。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        AppointmentSelection appointmentSelection = resolveAppointmentSelection(question, currentState);
        if (appointmentSelection == null || appointmentSelection.appointmentId() == null) {
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "我还没确定你想修改哪一条预约。你可以直接说“把预约13改到明天下午”或“改约最新预约”。",
                    "agent",
                    List.of(),
                    List.of(),
                    currentState
            );
        }

        if (parsedAppointmentTime == null) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_RESCHEDULE_INTENT",
                    "NEEDS_SCHEDULE",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    appointmentSelection.appointmentId(),
                    appointmentSelection.label(),
                    null,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "可以，我来帮你改约 %s。请告诉我新的预约时间，例如“明天下午”或“2026-04-10 15:00”。"
                            .formatted(appointmentSelection.label()),
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        return buildAppointmentRescheduleConfirmResponse(
                conversationId,
                currentState,
                appointmentSelection,
                parsedAppointmentTime.displayText()
        );
    }

    private AssistantChatResponseVo handleAppointmentRescheduleConfirmation(String question,
                                                                            String conversationId,
                                                                            AssistantTaskState currentState) {
        if (isNegativeConfirmation(question)) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_QUERY",
                    "COMPLETED",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    currentState.selectedAppointmentId(),
                    currentState.selectedAppointmentLabel(),
                    null,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "好的，这次先不改约，原预约时间保持不变。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        AppointmentTimeParser.ParsedAppointmentTime updatedAppointmentTime = AppointmentTimeParser.parse(question, ZONE_ID);
        if (updatedAppointmentTime != null) {
            return buildAppointmentRescheduleConfirmResponse(
                    conversationId,
                    currentState,
                    new AppointmentSelection(currentState.selectedAppointmentId(), currentState.selectedAppointmentLabel()),
                    updatedAppointmentTime.displayText()
            );
        }

        if (!isPositiveConfirmation(question)) {
            return null;
        }

        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_QUERY",
                    "NEEDS_LOGIN",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    currentState.selectedAppointmentId(),
                    currentState.selectedAppointmentLabel(),
                    currentState.proposedAppointmentTime(),
                    conversationSupport.safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return conversationSupport.buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能改约。请先登录后再试。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        return rescheduleAppointmentFromState(conversationId, currentState);
    }

    private AssistantChatResponseVo buildAppointmentIntentResponse(String conversationId,
                                                                  AssistantTaskState currentState,
                                                                  String reply) {
        AssistantTaskState nextState = new AssistantTaskState(
                "APPOINTMENT_INTENT",
                isLoggedIn() ? "NEEDS_SCHEDULE" : "NEEDS_LOGIN",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                null,
                conversationSupport.safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, nextState);
        return conversationSupport.buildLocalResponse(conversationId, reply, "agent", List.of(), List.of(), nextState);
    }

    private AssistantChatResponseVo buildAppointmentConfirmResponse(String conversationId,
                                                                   AssistantTaskState currentState,
                                                                   String appointmentTimeText) {
        AssistantTaskState nextState = new AssistantTaskState(
                "APPOINTMENT_CONFIRMING",
                "WAITING_CONFIRMATION",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                currentState.selectedAppointmentId(),
                currentState.selectedAppointmentLabel(),
                appointmentTimeText,
                conversationSupport.safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, nextState);
        String roomTitle = StringUtils.hasText(currentState.selectedRoomTitle()) ? currentState.selectedRoomTitle() : "当前房源";
        String reply = "我准备为你预约 **%s**，预约时间是 **%s**。如果确认，请回复“确认”；如果想修改时间，直接告诉我新的预约时间即可。"
                .formatted(roomTitle, appointmentTimeText);
        return conversationSupport.buildLocalResponse(conversationId, reply, "agent", List.of(), List.of(), nextState);
    }

    private AssistantChatResponseVo buildAppointmentCancelConfirmResponse(String conversationId,
                                                                          AssistantTaskState currentState,
                                                                          AppointmentSelection appointmentSelection) {
        AssistantTaskState nextState = new AssistantTaskState(
                "APPOINTMENT_CANCEL_CONFIRMING",
                "WAITING_CONFIRMATION",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                appointmentSelection.appointmentId(),
                appointmentSelection.label(),
                null,
                conversationSupport.safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, nextState);
        String reply = "我准备帮你取消 **%s**。如果确认取消，请回复“确认”；如果先不取消，回复“保留”或“取消”。"
                .formatted(appointmentSelection.label());
        return conversationSupport.buildLocalResponse(conversationId, reply, "agent", List.of(), List.of(), nextState);
    }

    private AssistantChatResponseVo buildAppointmentRescheduleConfirmResponse(String conversationId,
                                                                              AssistantTaskState currentState,
                                                                              AppointmentSelection appointmentSelection,
                                                                              String appointmentTimeText) {
        AssistantTaskState nextState = new AssistantTaskState(
                "APPOINTMENT_RESCHEDULE_CONFIRMING",
                "WAITING_CONFIRMATION",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                appointmentSelection.appointmentId(),
                appointmentSelection.label(),
                appointmentTimeText,
                conversationSupport.safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, nextState);
        String reply = "我准备把 **%s** 改约到 **%s**。如果确认修改，请回复“确认”；如果想换个时间，直接告诉我新的预约时间即可。"
                .formatted(appointmentSelection.label(), appointmentTimeText);
        return conversationSupport.buildLocalResponse(conversationId, reply, "agent", List.of(), List.of(), nextState);
    }

    private AssistantChatResponseVo createAppointmentFromState(String conversationId, AssistantTaskState currentState) {
        String toolArguments = "{\"roomId\":%d,\"appointmentTime\":\"%s\"}".formatted(
                currentState.selectedRoomId(),
                currentState.proposedAppointmentTime() == null ? "" : currentState.proposedAppointmentTime()
        );

        String toolResult = rentalAssistantTools.createRoomAppointment(
                currentState.selectedRoomId(),
                currentState.proposedAppointmentTime(),
                null
        );
        JsonNode resultNode = conversationSupport.parseToolResult(toolResult);
        boolean success = resultNode != null && resultNode.hasNonNull("appointmentId");

        AssistantToolExecutionVo toolExecution = new AssistantToolExecutionVo(
                "createRoomAppointment",
                toolArguments,
                !success,
                conversationSupport.summarizeToolResult(toolResult)
        );

        AssistantTaskState nextState;
        String reply;
        if (success) {
            Long roomId = conversationSupport.getLongValue(resultNode, "roomId");
            Long apartmentId = conversationSupport.getLongValue(resultNode, "apartmentId");
            Long appointmentId = conversationSupport.getLongValue(resultNode, "appointmentId");
            String roomTitle = conversationSupport.getTextValue(resultNode, "title");
            String appointmentTime = conversationSupport.getTextValue(resultNode, "appointmentTime");
            String appointmentLabel = conversationSupport.buildAppointmentLabel(
                    appointmentId,
                    StringUtils.hasText(roomTitle) ? roomTitle : currentState.selectedRoomTitle(),
                    appointmentTime
            );
            nextState = new AssistantTaskState(
                    "APPOINTMENT_CREATED",
                    "COMPLETED",
                    roomId == null ? currentState.selectedRoomId() : roomId,
                    StringUtils.hasText(roomTitle) ? roomTitle : currentState.selectedRoomTitle(),
                    apartmentId == null ? currentState.selectedApartmentId() : apartmentId,
                    appointmentId,
                    appointmentLabel,
                    appointmentTime,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            reply = StringUtils.hasText(conversationSupport.getTextValue(resultNode, "summary"))
                    ? conversationSupport.getTextValue(resultNode, "summary")
                    : "预约已经创建成功，你可以在“我的预约”里查看详情。";
        } else {
            nextState = new AssistantTaskState(
                    "APPOINTMENT_INTENT",
                    isLoggedIn() ? "NEEDS_SCHEDULE" : "NEEDS_LOGIN",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    null,
                    null,
                    null,
                    conversationSupport.safeCandidateRooms(currentState)
            );
            reply = resultNode != null && StringUtils.hasText(conversationSupport.getTextValue(resultNode, "summary"))
                    ? conversationSupport.getTextValue(resultNode, "summary")
                    : "当前没有成功创建预约，请稍后再试或换一个时间。";
        }

        assistantTaskStateStore.save(conversationId, nextState);
        return conversationSupport.buildLocalResponse(
                conversationId,
                reply,
                success ? "tool" : "agent",
                List.of(toolExecution),
                List.of(),
                nextState
        );
    }

    private AssistantChatResponseVo cancelAppointmentFromState(String conversationId, AssistantTaskState currentState) {
        String toolArguments = "{\"appointmentId\":%d}".formatted(currentState.selectedAppointmentId());
        String toolResult = rentalAssistantTools.cancelAppointment(currentState.selectedAppointmentId());
        JsonNode resultNode = conversationSupport.parseToolResult(toolResult);
        Integer appointmentStatusCode = resultNode != null && resultNode.hasNonNull("appointmentStatusCode")
                ? resultNode.get("appointmentStatusCode").asInt()
                : null;
        boolean success = resultNode != null
                && resultNode.hasNonNull("appointmentId")
                && appointmentStatusCode != null
                && appointmentStatusCode == 2;

        AssistantToolExecutionVo toolExecution = new AssistantToolExecutionVo(
                "cancelAppointment",
                toolArguments,
                !success,
                conversationSupport.summarizeToolResult(toolResult)
        );

        String appointmentLabel = currentState.selectedAppointmentLabel();
        if (resultNode != null) {
            String apartmentName = conversationSupport.getTextValue(resultNode, "apartmentName");
            String appointmentTime = conversationSupport.getTextValue(resultNode, "appointmentTime");
            if (StringUtils.hasText(apartmentName) || StringUtils.hasText(appointmentTime)) {
                appointmentLabel = conversationSupport.buildAppointmentLabel(
                        currentState.selectedAppointmentId(),
                        apartmentName,
                        appointmentTime
                );
            }
        }

        AssistantTaskState nextState = success
                ? new AssistantTaskState(
                "APPOINTMENT_CANCELED",
                "COMPLETED",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                currentState.selectedAppointmentId(),
                appointmentLabel,
                null,
                conversationSupport.safeCandidateRooms(currentState)
        )
                : new AssistantTaskState(
                "APPOINTMENT_QUERY",
                "COMPLETED",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                currentState.selectedAppointmentId(),
                appointmentLabel,
                null,
                conversationSupport.safeCandidateRooms(currentState)
        );

        assistantTaskStateStore.save(conversationId, nextState);
        String reply = resultNode != null && StringUtils.hasText(conversationSupport.getTextValue(resultNode, "summary"))
                ? conversationSupport.getTextValue(resultNode, "summary")
                : success ? "预约已经取消成功。" : "当前没有成功取消预约，请稍后再试。";
        return conversationSupport.buildLocalResponse(
                conversationId,
                reply,
                success ? "tool" : "agent",
                List.of(toolExecution),
                List.of(),
                nextState
        );
    }

    private AssistantChatResponseVo rescheduleAppointmentFromState(String conversationId, AssistantTaskState currentState) {
        String toolArguments = "{\"appointmentId\":%d,\"appointmentTime\":\"%s\"}".formatted(
                currentState.selectedAppointmentId(),
                currentState.proposedAppointmentTime() == null ? "" : currentState.proposedAppointmentTime()
        );
        String toolResult = rentalAssistantTools.rescheduleAppointment(
                currentState.selectedAppointmentId(),
                currentState.proposedAppointmentTime()
        );
        JsonNode resultNode = conversationSupport.parseToolResult(toolResult);
        Integer appointmentStatusCode = resultNode != null && resultNode.hasNonNull("appointmentStatusCode")
                ? resultNode.get("appointmentStatusCode").asInt()
                : null;
        boolean success = resultNode != null
                && resultNode.hasNonNull("appointmentId")
                && appointmentStatusCode != null
                && appointmentStatusCode == 1
                && StringUtils.hasText(conversationSupport.getTextValue(resultNode, "appointmentTime"));

        AssistantToolExecutionVo toolExecution = new AssistantToolExecutionVo(
                "rescheduleAppointment",
                toolArguments,
                !success,
                conversationSupport.summarizeToolResult(toolResult)
        );

        String appointmentLabel = currentState.selectedAppointmentLabel();
        String appointmentTime = currentState.proposedAppointmentTime();
        if (resultNode != null) {
            String apartmentName = conversationSupport.getTextValue(resultNode, "apartmentName");
            String updatedAppointmentTime = conversationSupport.getTextValue(resultNode, "appointmentTime");
            if (StringUtils.hasText(updatedAppointmentTime)) {
                appointmentTime = updatedAppointmentTime;
            }
            if (StringUtils.hasText(apartmentName) || StringUtils.hasText(appointmentTime)) {
                appointmentLabel = conversationSupport.buildAppointmentLabel(
                        currentState.selectedAppointmentId(),
                        apartmentName,
                        appointmentTime
                );
            }
        }

        AssistantTaskState nextState = success
                ? new AssistantTaskState(
                "APPOINTMENT_RESCHEDULED",
                "COMPLETED",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                currentState.selectedAppointmentId(),
                appointmentLabel,
                appointmentTime,
                conversationSupport.safeCandidateRooms(currentState)
        )
                : new AssistantTaskState(
                "APPOINTMENT_QUERY",
                "COMPLETED",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                currentState.selectedAppointmentId(),
                appointmentLabel,
                null,
                conversationSupport.safeCandidateRooms(currentState)
        );

        assistantTaskStateStore.save(conversationId, nextState);
        String reply = resultNode != null && StringUtils.hasText(conversationSupport.getTextValue(resultNode, "summary"))
                ? conversationSupport.getTextValue(resultNode, "summary")
                : success ? "已为你修改预约时间。" : "当前没有成功修改预约时间，请稍后再试。";
        return conversationSupport.buildLocalResponse(
                conversationId,
                reply,
                success ? "tool" : "agent",
                List.of(toolExecution),
                List.of(),
                nextState
        );
    }

    private AppointmentSelection resolveAppointmentSelection(String question, AssistantTaskState currentState) {
        Long explicitAppointmentId = extractAppointmentId(question);
        if (explicitAppointmentId != null) {
            if (currentState != null
                    && explicitAppointmentId.equals(currentState.selectedAppointmentId())
                    && StringUtils.hasText(currentState.selectedAppointmentLabel())) {
                return new AppointmentSelection(explicitAppointmentId, currentState.selectedAppointmentLabel());
            }
            return new AppointmentSelection(explicitAppointmentId, "预约ID " + explicitAppointmentId);
        }

        if (currentState != null && currentState.selectedAppointmentId() != null) {
            String label = StringUtils.hasText(currentState.selectedAppointmentLabel())
                    ? currentState.selectedAppointmentLabel()
                    : "预约ID " + currentState.selectedAppointmentId();
            return new AppointmentSelection(currentState.selectedAppointmentId(), label);
        }
        return null;
    }

    private AppointmentQueryContext loadAppointmentQueryContext() {
        try {
            JsonNode resultNode = conversationSupport.parseToolResult(rentalAssistantTools.getMyAppointments());
            if (resultNode == null) {
                return new AppointmentQueryContext(List.of());
            }

            JsonNode itemsNode = resultNode.path("items");
            if (!itemsNode.isArray()) {
                return new AppointmentQueryContext(List.of());
            }

            List<AppointmentSelection> waitingAppointments = new ArrayList<>();
            for (JsonNode itemNode : itemsNode) {
                Long appointmentId = conversationSupport.getLongValue(itemNode, "appointmentId");
                Integer appointmentStatusCode = itemNode.hasNonNull("appointmentStatusCode")
                        ? itemNode.get("appointmentStatusCode").asInt()
                        : null;
                if (appointmentId == null || appointmentStatusCode == null || appointmentStatusCode != 1) {
                    continue;
                }
                waitingAppointments.add(new AppointmentSelection(
                        appointmentId,
                        conversationSupport.buildAppointmentLabel(
                                appointmentId,
                                conversationSupport.getTextValue(itemNode, "apartmentName"),
                                conversationSupport.getTextValue(itemNode, "appointmentTime")
                        )
                ));
            }
            return new AppointmentQueryContext(List.copyOf(waitingAppointments));
        } catch (Exception e) {
            log.warn("Failed to load appointment query context from tool result", e);
            return new AppointmentQueryContext(List.of());
        }
    }

    private Long extractAppointmentId(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        Matcher matcher = APPOINTMENT_ID_PATTERN.matcher(question.trim());
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return null;
    }

    private boolean looksLikeAppointmentManagementQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String normalized = question.trim();
        return normalized.contains("\u53d6\u6d88\u9884\u7ea6")
                || normalized.contains("\u6700\u65b0\u9884\u7ea6")
                || normalized.contains("\u8fd9\u6761\u9884\u7ea6")
                || normalized.contains("\u521a\u521a\u7684\u9884\u7ea6")
                || normalized.contains("\u6539\u7ea6")
                || normalized.contains("\u6539\u9884\u7ea6")
                || normalized.contains("\u6539\u65f6\u95f4")
                || normalized.contains("\u6362\u65f6\u95f4")
                || normalized.contains("\u6539\u5230")
                || normalized.contains("\u6539\u6210");
    }

    private boolean mentionsLatestAppointment(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String normalized = question.trim();
        return normalized.contains("\u6700\u65b0\u9884\u7ea6")
                || normalized.contains("\u521a\u521a\u7684\u9884\u7ea6")
                || normalized.contains("\u521a\u521b\u5efa\u7684\u9884\u7ea6")
                || normalized.contains("\u8fd9\u6761\u9884\u7ea6")
                || normalized.contains("\u8fd9\u4e2a\u9884\u7ea6")
                || normalized.contains("\u7b2c\u4e00\u6761")
                || normalized.contains("\u7b2c1\u6761");
    }

    private boolean isLoggedIn() {
        return LoginUserHolder.get() != null;
    }

    private void runAsyncWithLoginContext(com.atguigu.lease.common.login.LoginUser loginUser,
                                          Authentication authentication,
                                          Runnable runnable) {
        CompletableFuture.runAsync(() -> {
            try {
                if (authentication != null) {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                } else {
                    SecurityContextHolder.clearContext();
                }
                if (loginUser != null) {
                    LoginUserHolder.set(loginUser);
                } else {
                    LoginUserHolder.clear();
                }
                runnable.run();
            } finally {
                LoginUserHolder.clear();
                SecurityContextHolder.clearContext();
            }
        });
    }

    private boolean isRoomAppointmentIntent(String question, AssistantTaskState currentState) {
        if (currentState == null || !StringUtils.hasText(question)) {
            return false;
        }
        if (!List.of("ROOM_DETAIL", "APPOINTMENT_INTENT", "APPOINTMENT_CONFIRMING").contains(currentState.taskType())) {
            return false;
        }
        String normalized = question.trim();
        return normalized.contains("预约") && !normalized.contains("我的预约");
    }

    private boolean isAppointmentCancelIntent(String question, AssistantTaskState currentState) {
        if (currentState == null || !StringUtils.hasText(question)) {
            return false;
        }
        if (!List.of("APPOINTMENT_QUERY", "APPOINTMENT_CREATED", "APPOINTMENT_CANCEL_CONFIRMING").contains(currentState.taskType())) {
            return false;
        }
        String normalized = question.trim();
        return normalized.contains("取消预约")
                || normalized.contains("取消最新预约")
                || normalized.contains("取消这条预约")
                || normalized.contains("取消刚刚的预约")
                || (normalized.contains("取消") && normalized.contains("预约"));
    }

    private boolean isAppointmentRescheduleIntent(String question, AssistantTaskState currentState) {
        if (currentState == null || !StringUtils.hasText(question)) {
            return false;
        }
        if (!List.of(
                "APPOINTMENT_QUERY",
                "APPOINTMENT_CREATED",
                "APPOINTMENT_RESCHEDULE_INTENT",
                "APPOINTMENT_RESCHEDULE_CONFIRMING"
        ).contains(currentState.taskType())) {
            return false;
        }
        String normalized = question.trim();
        return normalized.contains("改约")
                || normalized.contains("改预约")
                || normalized.contains("改时间")
                || normalized.contains("换时间")
                || normalized.contains("改到")
                || normalized.contains("改成");
    }

    private boolean isAppointmentAvailabilityQuestion(String question, AssistantTaskState currentState) {
        if (currentState == null || !"ROOM_DETAIL".equals(currentState.taskType()) || !StringUtils.hasText(question)) {
            return false;
        }
        String normalized = question.trim();
        return normalized.contains("可以预约") || normalized.contains("能预约") || normalized.contains("怎么预约");
    }

    private boolean isPositiveConfirmation(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String normalized = question.trim();
        return List.of("确认", "确认预约", "好的", "好", "可以", "行", "那就预约吧").contains(normalized);
    }

    private boolean isNegativeConfirmation(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String normalized = question.trim();
        return List.of("取消", "取消预约", "不用了", "算了", "先不预约", "保留").contains(normalized);
    }

    private Result<String> invokeAssistantWithRecovery(RentalAssistant rentalAssistant,
                                                       String conversationId,
                                                       String question) {
        try {
            return rentalAssistant.chat(conversationId, question);
        } catch (Exception firstException) {
            if (!isBrokenToolCallMemory(firstException) || !clearConversationMemory(conversationId)) {
                throw firstException;
            }

            log.warn("Assistant conversation memory was inconsistent and has been cleared, conversationId={}", conversationId, firstException);
            return rentalAssistant.chat(conversationId, question);
        }
    }

    private AssistantToolExecutionVo toToolExecutionVo(ToolExecution toolExecution) {
        String toolName = toolExecution == null || toolExecution.request() == null
                ? null
                : toolExecution.request().name();
        String arguments = toolExecution == null || toolExecution.request() == null
                ? null
                : toolExecution.request().arguments();
        boolean failed = toolExecution != null && toolExecution.hasFailed();
        String resultSummary = conversationSupport.summarizeToolResult(toolExecution == null ? null : toolExecution.result());
        return new AssistantToolExecutionVo(toolName, arguments, failed, resultSummary);
    }


    private void emitToolEvents(SseEmitter emitter, AssistantChatResponseVo response) {
        if (response.getToolExecutions() == null || response.getToolExecutions().isEmpty()) {
            return;
        }

        for (AssistantToolExecutionVo toolExecution : response.getToolExecutions()) {
            sendEvent(emitter, "tool_call", Map.of(
                    "conversationId", response.getConversationId(),
                    "toolName", blankToEmpty(toolExecution.getToolName()),
                    "toolArguments", blankToEmpty(toolExecution.getToolArguments())
            ));

            sendEvent(emitter, "tool_result", Map.of(
                    "conversationId", response.getConversationId(),
                    "toolName", blankToEmpty(toolExecution.getToolName()),
                    "toolArguments", blankToEmpty(toolExecution.getToolArguments()),
                    "toolResult", blankToEmpty(toolExecution.getResultSummary()),
                    "failed", Boolean.TRUE.equals(toolExecution.getFailed())
            ));
        }
    }

    private void logAssistantSuccess(String question, AssistantChatResponseVo response) {
        log.info(
                "Assistant chat completed, conversationId={}, answerSource={}, finishReason={}, toolCount={}, knowledgeCount={}, question={}",
                response.getConversationId(),
                response.getAnswerSource(),
                response.getFinishReason(),
                response.getToolExecutions() == null ? 0 : response.getToolExecutions().size(),
                response.getKnowledgeSources() == null ? 0 : response.getKnowledgeSources().size(),
                question
        );

        if (response.getToolExecutions() == null) {
            return;
        }

        for (AssistantToolExecutionVo toolExecution : response.getToolExecutions()) {
            log.info(
                    "Assistant tool executed, conversationId={}, toolName={}, failed={}, toolArguments={}, toolResult={}",
                    response.getConversationId(),
                    toolExecution.getToolName(),
                    toolExecution.getFailed(),
                    toolExecution.getToolArguments(),
                    toolExecution.getResultSummary()
            );
        }
    }

    private void emitErrorAndComplete(SseEmitter emitter, Throwable throwable, String conversationId) {
        log.warn("Assistant streaming failed, conversationId={}", conversationId, throwable);
        sendEvent(emitter, "error", Map.of(
                "message", classifyErrorMessage(throwable),
                "conversationId", resolveConversationId(conversationId)
        ));
        emitter.complete();
    }

    private String classifyErrorMessage(Throwable throwable) {
        if (throwable instanceof LeaseException leaseException) {
            return leaseException.getMessage();
        }

        String joinedMessage = collectMessages(throwable).toLowerCase(Locale.ROOT);
        if (joinedMessage.contains("未登录")) {
            return "请先登录后再查询预约或租约信息";
        }
        if (joinedMessage.contains("403") || joinedMessage.contains("forbidden") || joinedMessage.contains("access is forbidden")) {
            return "上游模型服务拒绝访问，请检查模型网关或 API Key";
        }
        if (joinedMessage.contains("503") || joinedMessage.contains("upstream_error") || joinedMessage.contains("bad response status code 503")) {
            return "上游模型服务暂时不可用(503)，请稍后重试";
        }
        if (joinedMessage.contains("tool_call_id") || joinedMessage.contains("tool_calls")) {
            return "会话上下文已损坏，请开启新会话后重试";
        }
        if (joinedMessage.contains("timeout")) {
            return "智能助手响应超时，请稍后重试";
        }
        if (joinedMessage.contains("invalid_request_error")
                || joinedMessage.contains("unexpected end-of-input")
                || joinedMessage.contains("json eof")) {
            return "模型工具调用失败，请稍后重试";
        }
        return "智能助手调用失败，请稍后重试";
    }

    private String collectMessages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(current.getMessage());
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    private boolean clearConversationMemory(String conversationId) {
        AssistantMongoChatMemoryStore memoryStore = assistantChatMemoryStoreProvider.getIfAvailable();
        if (memoryStore == null || !StringUtils.hasText(conversationId)) {
            return false;
        }
        memoryStore.clearConversation(conversationId);
        assistantTaskStateStore.clear(conversationId);
        return true;
    }

    private boolean isBrokenToolCallMemory(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (StringUtils.hasText(message)
                    && message.contains("tool_calls")
                    && (message.contains("tool_call_id") || message.contains("did not have response messages"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("Failed to send SSE event: {}", eventName, e);
            throw new RuntimeException(e);
        }
    }

    private String normalizeQuestion(String message) {
        if (!StringUtils.hasText(message)) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        return message.trim();
    }

    private String resolveConversationId(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            return conversationId.trim();
        }
        return "lease-chat-" + UUID.randomUUID();
    }

    private List<String> splitForStreaming(String reply) {
        if (!StringUtils.hasText(reply)) {
            return List.of(DEFAULT_EMPTY_REPLY);
        }

        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < reply.length(); i += STREAM_CHUNK_SIZE) {
            chunks.add(reply.substring(i, Math.min(reply.length(), i + STREAM_CHUNK_SIZE)));
        }
        return chunks;
    }

    private long resolveStreamTimeoutMillis() {
        long baseTimeout = assistantProperties.getTimeout() == null
                ? 60000L
                : assistantProperties.getTimeout().toMillis();
        int retries = assistantProperties.getMaxRetries() == null
                ? 2
                : Math.max(assistantProperties.getMaxRetries(), 0);
        long attempts = retries + 1L;
        // Synthetic stream waits for the full tool loop before emitting deltas,
        // so reserve budget for multiple upstream attempts plus the tool-response round.
        long timeoutBudget = baseTimeout * attempts * 2L + 15000L;
        return Math.max(timeoutBudget, 60000L);
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean shouldUseNativeStreaming(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        if (streamingRentalAssistantProvider.getIfAvailable() == null) {
            return false;
        }

        String normalized = question.trim().toLowerCase(Locale.ROOT);
        for (String hint : TOOL_HEAVY_HINTS) {
            if (normalized.contains(hint.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private record AppointmentQueryContext(List<AppointmentSelection> waitingAppointments) {
        private AppointmentSelection latestWaiting() {
            return waitingAppointments == null || waitingAppointments.isEmpty() ? null : waitingAppointments.get(0);
        }
    }


    private record AppointmentSelection(Long appointmentId, String label) {
    }
}
