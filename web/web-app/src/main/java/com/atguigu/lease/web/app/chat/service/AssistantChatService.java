package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.chat.agent.AppointmentTimeParser;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskState;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskStateStore;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.dto.AssistantKnowledgeSourceVo;
import com.atguigu.lease.web.app.chat.dto.AssistantNextActionVo;
import com.atguigu.lease.web.app.chat.dto.AssistantRoomCandidateVo;
import com.atguigu.lease.web.app.chat.dto.AssistantTaskStateVo;
import com.atguigu.lease.web.app.chat.dto.AssistantToolExecutionVo;
import com.atguigu.lease.web.app.chat.memory.AssistantMongoChatMemoryStore;
import com.atguigu.lease.web.app.chat.tool.RentalAssistantTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    private static final int KNOWLEDGE_PREVIEW_LIMIT = 180;
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
    private final ObjectMapper objectMapper;

    public AssistantChatResponseVo chat(String message) {
        return chat(message, null);
    }

    public AssistantChatResponseVo chat(String message, String conversationId) {
        String question = normalizeQuestion(message);
        String resolvedConversationId = resolveConversationId(conversationId);
        RentalAssistant rentalAssistant = requireAssistant();

        try {
            AssistantChatResponseVo agentResponse = handleAgentAction(question, resolvedConversationId);
            if (agentResponse != null) {
                logAssistantSuccess(question, agentResponse);
                return agentResponse;
            }
            Result<String> assistantResult = invokeAssistantWithRecovery(rentalAssistant, resolvedConversationId, question);
            AssistantChatResponseVo response = buildResponse(resolvedConversationId, assistantResult);
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
            requireAssistant();
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
            return emitter;
        }

        AssistantChatResponseVo localAgentResponse = handleAgentAction(question, resolvedConversationId);
        boolean nativeStreaming = localAgentResponse == null && shouldUseNativeStreaming(question);
        sendEvent(emitter, "start", Map.of(
                "message", "开始生成回复",
                "conversationId", resolvedConversationId,
                "streamMode", localAgentResponse != null ? "local-agent" : nativeStreaming ? "native" : "synthetic"
        ));

        if (localAgentResponse != null) {
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

        if (nativeStreaming) {
            StreamingRentalAssistant streamingAssistant = streamingRentalAssistantProvider.getIfAvailable();
            runAsyncWithLoginContext(currentLoginUser, currentAuthentication, () -> startNativeStream(
                    emitter,
                    resolvedConversationId,
                    question,
                    streamingAssistant
            ));
        } else {
            RentalAssistant rentalAssistant = rentalAssistantProvider.getIfAvailable();
            runAsyncWithLoginContext(currentLoginUser, currentAuthentication, () -> startSyntheticStream(
                    emitter,
                    resolvedConversationId,
                    question,
                    rentalAssistant
            ));
        }
        return emitter;
    }

    private RentalAssistant requireAssistant() {
        if (!assistantProperties.isEnabled()) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手未启用");
        }

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
            AssistantChatResponseVo response = buildResponse(conversationId, assistantResult);
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

        sendEvent(emitter, "complete", buildCompletePayload(response));
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
                                      ChatResponse chatResponse) {
        AiMessage aiMessage = chatResponse == null ? null : chatResponse.aiMessage();
        String reply = aiMessage == null ? null : aiMessage.text();
        if (!StringUtils.hasText(reply)) {
            reply = contentBuilder.toString();
        }

        String formattedReply = formatReply(reply);
        AssistantChatResponseVo response = new AssistantChatResponseVo(
                conversationId,
                formattedReply,
                splitParagraphs(formattedReply),
                toolExecutions.isEmpty() ? "model" : "tool",
                chatResponse == null || chatResponse.finishReason() == null
                        ? "unknown"
                        : chatResponse.finishReason().name().toLowerCase(Locale.ROOT),
                List.copyOf(toolExecutions),
                List.of(),
                null,
                List.of()
        );
        sendEvent(emitter, "complete", buildCompletePayload(response));
        emitter.complete();
        logAssistantSuccess(question, response);
    }

    private AssistantChatResponseVo handleAgentAction(String question, String conversationId) {
        AssistantTaskState currentState = assistantTaskStateStore.get(conversationId);
        if (currentState == null || !StringUtils.hasText(question)) {
            log.debug("Assistant agent skipped, conversationId={}, hasState={}, questionPresent={}",
                    conversationId,
                    currentState != null,
                    StringUtils.hasText(question));
            return null;
        }

        boolean loggedIn = isLoggedIn();
        boolean confirmationFlow = "APPOINTMENT_CONFIRMING".equals(currentState.taskType());
        boolean cancelConfirmationFlow = "APPOINTMENT_CANCEL_CONFIRMING".equals(currentState.taskType());
        boolean availabilityQuestion = isAppointmentAvailabilityQuestion(question, currentState);
        boolean appointmentIntent = isRoomAppointmentIntent(question, currentState);
        boolean appointmentCancelIntent = isAppointmentCancelIntent(question, currentState);
        log.info(
                "Assistant agent decision, conversationId={}, taskType={}, taskStatus={}, loggedIn={}, confirmationFlow={}, cancelConfirmationFlow={}, availabilityQuestion={}, appointmentIntent={}, appointmentCancelIntent={}, question={}",
                conversationId,
                currentState.taskType(),
                currentState.taskStatus(),
                loggedIn,
                confirmationFlow,
                cancelConfirmationFlow,
                availabilityQuestion,
                appointmentIntent,
                appointmentCancelIntent,
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

        if (availabilityQuestion) {
            return buildAppointmentIntentResponse(conversationId, currentState, loggedIn
                    ? "这个房源可以预约。告诉我你希望预约的时间，例如“明天下午”或“2026-04-10 15:00”，我就继续帮你安排。"
                    : "这个房源可以预约，但你还没有登录。请先登录，登录后告诉我预约时间，我就继续帮你安排。");
        }

        if (appointmentIntent) {
            return handleAppointmentIntent(question, conversationId, currentState);
        }

        if (appointmentCancelIntent) {
            return handleAppointmentCancelIntent(question, conversationId, currentState);
        }

        return null;
    }

    private AssistantChatResponseVo handleAppointmentIntent(String question,
                                                            String conversationId,
                                                            AssistantTaskState currentState) {
        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState(
                    "APPOINTMENT_INTENT",
                    "NEEDS_LOGIN",
                    currentState.selectedRoomId(),
                    currentState.selectedRoomTitle(),
                    currentState.selectedApartmentId(),
                    null,
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能直接创建预约。请先登录，登录后告诉我预约时间，我会继续帮你安排看房。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        AppointmentTimeParser.ParsedAppointmentTime parsedAppointmentTime = AppointmentTimeParser.parse(question, ZONE_ID);
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
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, revertedState);
            return buildLocalResponse(
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
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
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
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
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
            return buildLocalResponse(
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
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
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
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
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
                safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, nextState);
        return buildLocalResponse(conversationId, reply, "agent", List.of(), List.of(), nextState);
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
                safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, nextState);
        String roomTitle = StringUtils.hasText(currentState.selectedRoomTitle()) ? currentState.selectedRoomTitle() : "当前房源";
        String reply = "我准备为你预约 **%s**，预约时间是 **%s**。如果确认，请回复“确认”；如果想修改时间，直接告诉我新的预约时间即可。"
                .formatted(roomTitle, appointmentTimeText);
        return buildLocalResponse(conversationId, reply, "agent", List.of(), List.of(), nextState);
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
                safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, nextState);
        String reply = "我准备帮你取消 **%s**。如果确认取消，请回复“确认”；如果先不取消，回复“保留”或“取消”。"
                .formatted(appointmentSelection.label());
        return buildLocalResponse(conversationId, reply, "agent", List.of(), List.of(), nextState);
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
        JsonNode resultNode = parseToolResult(toolResult);
        boolean success = resultNode != null && resultNode.hasNonNull("appointmentId");

        AssistantToolExecutionVo toolExecution = new AssistantToolExecutionVo(
                "createRoomAppointment",
                toolArguments,
                !success,
                summarizeToolResult(toolResult)
        );

        AssistantTaskState nextState;
        String reply;
        if (success) {
            Long roomId = getLongValue(resultNode, "roomId");
            Long apartmentId = getLongValue(resultNode, "apartmentId");
            Long appointmentId = getLongValue(resultNode, "appointmentId");
            String roomTitle = getTextValue(resultNode, "title");
            String appointmentTime = getTextValue(resultNode, "appointmentTime");
            String appointmentLabel = buildAppointmentLabel(
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
                    safeCandidateRooms(currentState)
            );
            reply = StringUtils.hasText(getTextValue(resultNode, "summary"))
                    ? getTextValue(resultNode, "summary")
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
                    safeCandidateRooms(currentState)
            );
            reply = resultNode != null && StringUtils.hasText(getTextValue(resultNode, "summary"))
                    ? getTextValue(resultNode, "summary")
                    : "当前没有成功创建预约，请稍后再试或换一个时间。";
        }

        assistantTaskStateStore.save(conversationId, nextState);
        return buildLocalResponse(
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
        JsonNode resultNode = parseToolResult(toolResult);
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
                summarizeToolResult(toolResult)
        );

        String appointmentLabel = currentState.selectedAppointmentLabel();
        if (resultNode != null) {
            String apartmentName = getTextValue(resultNode, "apartmentName");
            String appointmentTime = getTextValue(resultNode, "appointmentTime");
            if (StringUtils.hasText(apartmentName) || StringUtils.hasText(appointmentTime)) {
                appointmentLabel = buildAppointmentLabel(
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
                safeCandidateRooms(currentState)
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
                safeCandidateRooms(currentState)
        );

        assistantTaskStateStore.save(conversationId, nextState);
        String reply = resultNode != null && StringUtils.hasText(getTextValue(resultNode, "summary"))
                ? getTextValue(resultNode, "summary")
                : success ? "已为你取消预约。" : "当前没有成功取消预约，请稍后再试。";
        return buildLocalResponse(
                conversationId,
                reply,
                success ? "tool" : "agent",
                List.of(toolExecution),
                List.of(),
                nextState
        );
    }

    private AssistantChatResponseVo buildLocalResponse(String conversationId,
                                                       String reply,
                                                       String answerSource,
                                                       List<AssistantToolExecutionVo> toolExecutions,
                                                       List<AssistantKnowledgeSourceVo> knowledgeSources,
                                                       AssistantTaskState taskState) {
        String formattedReply = formatReply(reply);
        return new AssistantChatResponseVo(
                conversationId,
                formattedReply,
                splitParagraphs(formattedReply),
                answerSource,
                "agent",
                toolExecutions,
                knowledgeSources,
                toTaskStateVo(taskState),
                buildNextActions(taskState)
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

    private String buildAppointmentLabel(Long appointmentId, String apartmentName, String appointmentTime) {
        List<String> parts = new ArrayList<>();
        if (appointmentId != null) {
            parts.add("预约ID " + appointmentId);
        }
        if (StringUtils.hasText(apartmentName)) {
            parts.add(apartmentName.trim());
        }
        if (StringUtils.hasText(appointmentTime)) {
            parts.add(appointmentTime.trim());
        }
        return parts.isEmpty() ? "当前预约" : String.join(" / ", parts);
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

    private List<AssistantTaskState.RoomCandidate> safeCandidateRooms(AssistantTaskState currentState) {
        return currentState == null || currentState.candidateRooms() == null
                ? List.of()
                : currentState.candidateRooms();
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

    private AssistantChatResponseVo buildResponse(String conversationId, Result<String> assistantResult) {
        String formattedReply = formatReply(assistantResult == null ? null : assistantResult.content());
        List<String> paragraphs = splitParagraphs(formattedReply);
        List<AssistantToolExecutionVo> toolExecutions = buildToolExecutions(assistantResult);
        List<AssistantKnowledgeSourceVo> knowledgeSources = buildKnowledgeSources(assistantResult);
        AssistantTaskState taskState = resolveTaskState(conversationId, assistantResult);
        List<AssistantNextActionVo> nextActions = buildNextActions(taskState);

        return new AssistantChatResponseVo(
                conversationId,
                formattedReply,
                paragraphs,
                resolveAnswerSource(toolExecutions, knowledgeSources),
                resolveFinishReason(assistantResult),
                toolExecutions,
                knowledgeSources,
                toTaskStateVo(taskState),
                nextActions
        );
    }

    private List<AssistantToolExecutionVo> buildToolExecutions(Result<String> assistantResult) {
        if (assistantResult == null || assistantResult.toolExecutions() == null || assistantResult.toolExecutions().isEmpty()) {
            return List.of();
        }

        return assistantResult.toolExecutions().stream()
                .map(this::toToolExecutionVo)
                .toList();
    }

    private AssistantToolExecutionVo toToolExecutionVo(ToolExecution toolExecution) {
        String toolName = toolExecution == null || toolExecution.request() == null
                ? null
                : toolExecution.request().name();
        String arguments = toolExecution == null || toolExecution.request() == null
                ? null
                : toolExecution.request().arguments();
        boolean failed = toolExecution != null && toolExecution.hasFailed();
        String resultSummary = summarizeToolResult(toolExecution == null ? null : toolExecution.result());
        return new AssistantToolExecutionVo(toolName, arguments, failed, resultSummary);
    }

    private List<AssistantKnowledgeSourceVo> buildKnowledgeSources(Result<String> assistantResult) {
        if (assistantResult == null || assistantResult.sources() == null || assistantResult.sources().isEmpty()) {
            return List.of();
        }

        List<AssistantKnowledgeSourceVo> sources = new ArrayList<>();
        for (Content source : assistantResult.sources()) {
            if (source == null || source.textSegment() == null || !StringUtils.hasText(source.textSegment().text())) {
                continue;
            }
            String preview = source.textSegment().text().replace("\r\n", "\n").replace('\r', '\n').trim();
            if (preview.length() > KNOWLEDGE_PREVIEW_LIMIT) {
                preview = preview.substring(0, KNOWLEDGE_PREVIEW_LIMIT) + "...";
            }
            sources.add(new AssistantKnowledgeSourceVo(preview));
        }
        return sources;
    }

    private String resolveAnswerSource(List<AssistantToolExecutionVo> toolExecutions,
                                       List<AssistantKnowledgeSourceVo> knowledgeSources) {
        boolean hasTools = toolExecutions != null && !toolExecutions.isEmpty();
        boolean hasKnowledge = knowledgeSources != null && !knowledgeSources.isEmpty();
        if (hasTools && hasKnowledge) {
            return "tool+rag";
        }
        if (hasTools) {
            return "tool";
        }
        if (hasKnowledge) {
            return "rag";
        }
        return "model";
    }

    private String resolveFinishReason(Result<String> assistantResult) {
        if (assistantResult == null || assistantResult.finishReason() == null) {
            return "unknown";
        }
        return assistantResult.finishReason().name().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> buildCompletePayload(AssistantChatResponseVo response) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", response.getConversationId());
        payload.put("reply", response.getReply());
        payload.put("paragraphs", response.getParagraphs());
        payload.put("answerSource", response.getAnswerSource());
        payload.put("finishReason", response.getFinishReason());
        payload.put("toolExecutions", response.getToolExecutions());
        payload.put("knowledgeSources", response.getKnowledgeSources());
        payload.put("taskState", response.getTaskState());
        payload.put("nextActions", response.getNextActions());
        return payload;
    }

    private AssistantTaskState resolveTaskState(String conversationId, Result<String> assistantResult) {
        AssistantTaskState previousState = assistantTaskStateStore.get(conversationId);
        AssistantTaskState nextState = deriveTaskState(assistantResult, previousState);
        if (nextState != null) {
            assistantTaskStateStore.save(conversationId, nextState);
            return nextState;
        }
        return previousState;
    }

    private AssistantTaskState deriveTaskState(Result<String> assistantResult, AssistantTaskState previousState) {
        if (assistantResult != null && assistantResult.toolExecutions() != null) {
            for (ToolExecution toolExecution : assistantResult.toolExecutions()) {
                AssistantTaskState derivedState = buildTaskStateFromToolExecution(toolExecution, previousState);
                if (derivedState != null) {
                    return derivedState;
                }
            }
        }
        if (assistantResult != null && assistantResult.sources() != null && !assistantResult.sources().isEmpty()) {
            return new AssistantTaskState("KNOWLEDGE_QA", "COMPLETED", null, null, null, null, List.of());
        }
        return previousState;
    }

    private AssistantTaskState buildTaskStateFromToolExecution(ToolExecution toolExecution, AssistantTaskState previousState) {
        if (toolExecution == null || toolExecution.request() == null) {
            return null;
        }

        String toolName = toolExecution.request().name();
        JsonNode resultNode = parseToolResult(toolExecution.result());
        if (resultNode == null) {
            return null;
        }

        if ("searchRooms".equals(toolName)) {
            List<AssistantTaskState.RoomCandidate> candidates = extractRoomCandidates(resultNode.path("items"));
            String taskStatus = candidates.isEmpty() ? "NEEDS_REFINEMENT" : "WAITING_USER_INPUT";
            return new AssistantTaskState("ROOM_SEARCH", taskStatus, null, null, null, null, candidates);
        }

        if ("getRoomDetail".equals(toolName) || "getRoomDetailByKeyword".equals(toolName)) {
            Long roomId = getLongValue(resultNode, "roomId");
            String title = getTextValue(resultNode, "title");
            Long apartmentId = getLongValue(resultNode, "apartmentId");
            List<AssistantTaskState.RoomCandidate> candidates = previousState == null
                    ? List.of()
                    : previousState.candidateRooms();
            return new AssistantTaskState("ROOM_DETAIL", "WAITING_USER_INPUT", roomId, title, apartmentId, null, candidates);
        }

        if ("createRoomAppointment".equals(toolName)) {
            Long roomId = getLongValue(resultNode, "roomId");
            Long apartmentId = getLongValue(resultNode, "apartmentId");
            Long appointmentId = getLongValue(resultNode, "appointmentId");
            String title = getTextValue(resultNode, "title");
            String appointmentTime = getTextValue(resultNode, "appointmentTime");
            List<AssistantTaskState.RoomCandidate> candidates = previousState == null
                    ? List.of()
                    : previousState.candidateRooms();
            return new AssistantTaskState(
                    "APPOINTMENT_CREATED",
                    "COMPLETED",
                    roomId,
                    title,
                    apartmentId,
                    appointmentId,
                    buildAppointmentLabel(appointmentId, title, appointmentTime),
                    appointmentTime,
                    candidates
            );
        }

        if ("cancelAppointment".equals(toolName)) {
            Long appointmentId = getLongValue(resultNode, "appointmentId");
            String apartmentName = getTextValue(resultNode, "apartmentName");
            String appointmentTime = getTextValue(resultNode, "appointmentTime");
            return new AssistantTaskState(
                    "APPOINTMENT_CANCELED",
                    "COMPLETED",
                    previousState == null ? null : previousState.selectedRoomId(),
                    previousState == null ? null : previousState.selectedRoomTitle(),
                    getLongValue(resultNode, "apartmentId"),
                    appointmentId,
                    buildAppointmentLabel(appointmentId, apartmentName, appointmentTime),
                    null,
                    previousState == null ? List.of() : safeCandidateRooms(previousState)
            );
        }

        if ("getMyAppointments".equals(toolName)) {
            AppointmentSelection appointmentSelection = extractCancelableAppointment(resultNode.path("items"));
            return new AssistantTaskState(
                    "APPOINTMENT_QUERY",
                    "COMPLETED",
                    null,
                    null,
                    null,
                    appointmentSelection == null ? null : appointmentSelection.appointmentId(),
                    appointmentSelection == null ? null : appointmentSelection.label(),
                    null,
                    List.of()
            );
        }

        if ("getMyLeaseAgreements".equals(toolName)) {
            return new AssistantTaskState("LEASE_QUERY", "COMPLETED", null, null, null, null, List.of());
        }

        return previousState;
    }

    private List<AssistantTaskState.RoomCandidate> extractRoomCandidates(JsonNode itemsNode) {
        if (itemsNode == null || !itemsNode.isArray()) {
            return List.of();
        }

        List<AssistantTaskState.RoomCandidate> candidates = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            Long roomId = getLongValue(itemNode, "roomId");
            String title = getTextValue(itemNode, "title");
            if (roomId == null || !StringUtils.hasText(title)) {
                continue;
            }
            candidates.add(new AssistantTaskState.RoomCandidate(roomId, title));
        }
        return List.copyOf(candidates);
    }

    private AppointmentSelection extractCancelableAppointment(JsonNode itemsNode) {
        if (itemsNode == null || !itemsNode.isArray()) {
            return null;
        }
        for (JsonNode itemNode : itemsNode) {
            Long appointmentId = getLongValue(itemNode, "appointmentId");
            Integer appointmentStatusCode = itemNode.hasNonNull("appointmentStatusCode")
                    ? itemNode.get("appointmentStatusCode").asInt()
                    : null;
            if (appointmentId == null || appointmentStatusCode == null || appointmentStatusCode != 1) {
                continue;
            }
            return new AppointmentSelection(
                    appointmentId,
                    buildAppointmentLabel(
                            appointmentId,
                            getTextValue(itemNode, "apartmentName"),
                            getTextValue(itemNode, "appointmentTime")
                    )
            );
        }
        return null;
    }

    private List<AssistantNextActionVo> buildNextActions(AssistantTaskState taskState) {
        if (taskState == null || !StringUtils.hasText(taskState.taskType())) {
            return List.of();
        }

        List<AssistantNextActionVo> actions = new ArrayList<>();
        if ("APPOINTMENT_CREATED".equals(taskState.taskType()) && taskState.selectedAppointmentId() != null) {
            return List.of(
                    new AssistantNextActionVo("VIEW_APPOINTMENTS", "查看我的预约", "帮我看一下我的预约", null, false),
                    new AssistantNextActionVo("CANCEL_CURRENT_APPOINTMENT", "取消这次预约", "取消刚刚的预约", null, true),
                    new AssistantNextActionVo("SEARCH_MORE_ROOMS", "继续看看其他房源", "再给我推荐几套房源", null, false)
            );
        }
        if ("APPOINTMENT_QUERY".equals(taskState.taskType()) && taskState.selectedAppointmentId() != null) {
            return List.of(
                    new AssistantNextActionVo("CANCEL_LATEST_APPOINTMENT", "取消最新预约", "取消最新预约", null, true),
                    new AssistantNextActionVo("VIEW_LEASES", "查看我的租约", "帮我看看我的租约", null)
            );
        }
        if ("APPOINTMENT_CANCEL_CONFIRMING".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("CONFIRM_CANCEL_APPOINTMENT", "确认取消", "确认", null, true),
                    new AssistantNextActionVo("KEEP_APPOINTMENT", "保留这条预约", "保留", null, false)
            );
        }
        if ("APPOINTMENT_CANCELED".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("VIEW_APPOINTMENTS", "再看一下我的预约", "帮我看一下我的预约", null, false),
                    new AssistantNextActionVo("SEARCH_MORE_ROOMS", "继续看看其他房源", "再给我推荐几套房源", null, false)
            );
        }
        if ("ROOM_SEARCH".equals(taskState.taskType())) {
            List<AssistantTaskState.RoomCandidate> candidates = taskState.candidateRooms() == null
                    ? List.of()
                    : taskState.candidateRooms();
            for (int i = 0; i < Math.min(candidates.size(), 3); i++) {
                AssistantTaskState.RoomCandidate candidate = candidates.get(i);
                actions.add(new AssistantNextActionVo(
                        "VIEW_ROOM_DETAIL",
                        "查看第" + (i + 1) + "个房源",
                        candidate.title() + " 介绍一下",
                        candidate.roomId()
                ));
            }
            if (candidates.isEmpty()) {
                actions.add(new AssistantNextActionVo(
                        "REFINE_ROOM_SEARCH",
                        "放宽条件重新查找",
                        "帮我查一下北京市 3500 以内的房源",
                        null
                ));
            } else {
                actions.add(new AssistantNextActionVo(
                        "REFINE_ROOM_SEARCH",
                        "换个条件再查",
                        "帮我再查一下其他条件的房源",
                        null
                ));
            }
            return List.copyOf(actions);
        }

        if ("ROOM_DETAIL".equals(taskState.taskType())) {
            actions.add(new AssistantNextActionVo(
                    "ASK_APPOINTMENT",
                    "问这个房源能否预约",
                    "这个房源可以预约吗",
                    taskState.selectedRoomId()
            ));
            actions.add(new AssistantNextActionVo(
                    "SEARCH_MORE_ROOMS",
                    "继续看看其他房源",
                    "再给我推荐几套房源",
                    null
            ));
            return List.copyOf(actions);
        }

        if ("APPOINTMENT_INTENT".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("SCHEDULE_TOMORROW_AFTERNOON", "预约明天下午", "帮我预约明天下午", taskState.selectedRoomId(), false),
                    new AssistantNextActionVo("SCHEDULE_TOMORROW_MORNING", "预约明天上午", "帮我预约明天上午", taskState.selectedRoomId(), false),
                    new AssistantNextActionVo("VIEW_APPOINTMENTS", "查看我的预约", "帮我看一下我的预约", null, false)
            );
        }

        if ("APPOINTMENT_CONFIRMING".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("CONFIRM_APPOINTMENT", "确认预约", "确认", taskState.selectedRoomId(), true),
                    new AssistantNextActionVo("CANCEL_APPOINTMENT", "取消本次预约", "取消", taskState.selectedRoomId(), false)
            );
        }

        if ("APPOINTMENT_CREATED".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("VIEW_APPOINTMENTS", "查看我的预约", "帮我看一下我的预约", null, false),
                    new AssistantNextActionVo("SEARCH_MORE_ROOMS", "继续看看其他房源", "再给我推荐几套房源", null, false)
            );
        }

        if ("APPOINTMENT_QUERY".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("VIEW_LEASES", "查看我的租约", "帮我看看我的租约", null)
            );
        }

        if ("LEASE_QUERY".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("VIEW_APPOINTMENTS", "查看我的预约", "帮我看看我的预约", null)
            );
        }

        if ("KNOWLEDGE_QA".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("SEARCH_ROOMS", "开始查房源", "帮我查一下北京市 3000 以内的房源", null)
            );
        }

        return List.of();
    }

    private AssistantTaskStateVo toTaskStateVo(AssistantTaskState taskState) {
        if (taskState == null) {
            return null;
        }
        List<AssistantRoomCandidateVo> candidates = taskState.candidateRooms() == null
                ? List.of()
                : taskState.candidateRooms().stream()
                .map(candidate -> new AssistantRoomCandidateVo(candidate.roomId(), candidate.title()))
                .toList();
        return new AssistantTaskStateVo(
                taskState.taskType(),
                taskState.taskStatus(),
                taskState.selectedRoomId(),
                taskState.selectedRoomTitle(),
                taskState.selectedApartmentId(),
                taskState.selectedAppointmentId(),
                taskState.selectedAppointmentLabel(),
                taskState.proposedAppointmentTime(),
                candidates
        );
    }

    private JsonNode parseToolResult(String toolResult) {
        if (!StringUtils.hasText(toolResult)) {
            return null;
        }
        try {
            return objectMapper.readTree(toolResult);
        } catch (Exception e) {
            log.debug("Failed to parse tool result as JSON", e);
            return null;
        }
    }

    private Long getLongValue(JsonNode node, String fieldName) {
        if (node == null || !node.hasNonNull(fieldName)) {
            return null;
        }
        JsonNode valueNode = node.get(fieldName);
        return valueNode.canConvertToLong() ? valueNode.longValue() : null;
    }

    private String getTextValue(JsonNode node, String fieldName) {
        if (node == null || !node.hasNonNull(fieldName)) {
            return null;
        }
        String value = node.get(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
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

    private String summarizeToolResult(String toolResult) {
        if (!StringUtils.hasText(toolResult)) {
            return "工具已执行完成";
        }
        String normalized = toolResult.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
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

    private String formatReply(String reply) {
        if (!StringUtils.hasText(reply)) {
            return DEFAULT_EMPTY_REPLY;
        }

        String normalized = reply
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceFirst("^```[a-zA-Z]*\\n?", "")
                .replaceFirst("\\n?```$", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ");

        return normalized.trim();
    }

    private List<String> splitParagraphs(String reply) {
        return Arrays.stream(reply.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
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

    private record AppointmentSelection(Long appointmentId, String label) {
    }
}
