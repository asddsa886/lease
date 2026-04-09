package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatService {

    private static final String DEFAULT_EMPTY_REPLY = "我当前没有生成有效回复，请换个问法再试。";
    private static final int STREAM_CHUNK_SIZE = 24;
    private static final int KNOWLEDGE_PREVIEW_LIMIT = 180;
    private static final List<String> TOOL_HEAVY_HINTS = List.of(
            "房源", "房间", "房号", "公寓", "小区", "预约", "租约", "租房",
            "月租", "租金", "朝阳", "海淀", "昌平", "通州", "北京市", "押一付三",
            "流程", "退租", "续约", "详情", "介绍", "第二个", "这个", "那个"
    );

    private final AssistantProperties assistantProperties;
    private final ObjectProvider<RentalAssistant> rentalAssistantProvider;
    private final ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;
    private final ObjectProvider<AssistantMongoChatMemoryStore> assistantChatMemoryStoreProvider;
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

        try {
            question = normalizeQuestion(message);
            resolvedConversationId = resolveConversationId(conversationId);
            requireAssistant();
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
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
            CompletableFuture.runAsync(() -> startNativeStream(
                    emitter,
                    resolvedConversationId,
                    question,
                    streamingAssistant
            ));
        } else {
            RentalAssistant rentalAssistant = rentalAssistantProvider.getIfAvailable();
            CompletableFuture.runAsync(() -> startSyntheticStream(
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
            emitToolEvents(emitter, response);
            for (String chunk : splitForStreaming(response.getReply())) {
                if (!StringUtils.hasText(chunk)) {
                    continue;
                }
                sendEvent(emitter, "delta", Map.of(
                        "content", chunk,
                        "conversationId", conversationId
                ));
                TimeUnit.MILLISECONDS.sleep(18);
            }

            sendEvent(emitter, "complete", buildCompletePayload(response));
            emitter.complete();
            logAssistantSuccess(question, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitErrorAndComplete(emitter, e, conversationId);
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
        }
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
            return new AssistantTaskState("KNOWLEDGE_QA", "COMPLETED", null, null, List.of());
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
            return new AssistantTaskState("ROOM_SEARCH", taskStatus, null, null, candidates);
        }

        if ("getRoomDetail".equals(toolName) || "getRoomDetailByKeyword".equals(toolName)) {
            Long roomId = getLongValue(resultNode, "roomId");
            String title = getTextValue(resultNode, "title");
            List<AssistantTaskState.RoomCandidate> candidates = previousState == null
                    ? List.of()
                    : previousState.candidateRooms();
            return new AssistantTaskState("ROOM_DETAIL", "WAITING_USER_INPUT", roomId, title, candidates);
        }

        if ("getMyAppointments".equals(toolName)) {
            return new AssistantTaskState("APPOINTMENT_QUERY", "COMPLETED", null, null, List.of());
        }

        if ("getMyLeaseAgreements".equals(toolName)) {
            return new AssistantTaskState("LEASE_QUERY", "COMPLETED", null, null, List.of());
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

    private List<AssistantNextActionVo> buildNextActions(AssistantTaskState taskState) {
        if (taskState == null || !StringUtils.hasText(taskState.taskType())) {
            return List.of();
        }

        List<AssistantNextActionVo> actions = new ArrayList<>();
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
}
