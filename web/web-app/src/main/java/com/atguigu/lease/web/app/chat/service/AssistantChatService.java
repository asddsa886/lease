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
import java.math.BigDecimal;
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
    private static final Pattern REGION_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,12}(?:市|区|县|旗|州|省))");
    private static final Pattern RENT_RANGE_PATTERN = Pattern.compile("(\\d{3,6})(?:\\s*元|块)?\\s*(?:到|-|~|～)\\s*(\\d{3,6})");
    private static final Pattern MAX_RENT_PATTERN = Pattern.compile("(\\d{3,6})(?:\\s*元|块)?\\s*(?:以内|以下|之内|左右|封顶)");
    private static final Pattern MIN_RENT_PATTERN = Pattern.compile("(\\d{3,6})(?:\\s*元|块)?\\s*(?:以上|起|及以上)");
    private static final Pattern ROOM_INDEX_PATTERN = Pattern.compile("第\\s*([1-9]\\d*)\\s*(?:个|套|间)?");
    private static final List<String> TOOL_HEAVY_HINTS = List.of(
            "房源", "房间", "房号", "公寓", "小区", "预约", "租约", "租房",
            "月租", "租金", "朝阳", "海淀", "昌平", "通州", "北京市", "押一付三",
            "流程", "退租", "续约", "详情", "介绍", "第二个", "这个", "那个"
    );

    private final AssistantProperties assistantProperties;
    private final ObjectProvider<RentalAssistant> rentalAssistantProvider;
    private final ObjectProvider<ToolFirstRentalAssistant> toolFirstRentalAssistantProvider;
    private final ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;
    private final ObjectProvider<StreamingToolFirstRentalAssistant> streamingToolFirstRentalAssistantProvider;
    private final ObjectProvider<AppointmentActionAnalyzer> appointmentActionAnalyzerProvider;
    private final ObjectProvider<AssistantMongoChatMemoryStore> assistantChatMemoryStoreProvider;
    private final RentalAssistantTools rentalAssistantTools;
    private final AssistantTaskStateStore assistantTaskStateStore;
    private final AssistantWorkflowOrchestrator assistantWorkflowOrchestrator;
    private final ObjectMapper objectMapper;

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
        AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult = orchestrateQuestion(question, resolvedConversationId);
        AssistantChatResponseVo deterministicToolResponse = handleDeterministicOrchestratedTool(resolvedConversationId, orchestrationResult);
        if (deterministicToolResponse != null) {
            logAssistantSuccess(question, deterministicToolResponse);
            return deterministicToolResponse;
        }
        RentalAssistant rentalAssistant = selectAssistant(orchestrationResult);

        try {
            Result<String> assistantResult = invokeAssistantWithRecovery(rentalAssistant, resolvedConversationId, orchestrationResult);
            AssistantChatResponseVo response = buildResponse(resolvedConversationId, assistantResult, orchestrationResult);
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

        AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult = orchestrateQuestion(question, resolvedConversationId);
        AssistantChatResponseVo deterministicToolResponse = handleDeterministicOrchestratedTool(resolvedConversationId, orchestrationResult);
        if (deterministicToolResponse != null) {
            sendEvent(emitter, "start", Map.of(
                    "message", "开始生成回复",
                    "conversationId", resolvedConversationId,
                    "streamMode", "deterministic-tool"
            ));
            runAsyncWithLoginContext(currentLoginUser, currentAuthentication, () -> {
                try {
                    emitSyntheticResponse(emitter, extractOriginalQuestion(orchestrationResult), deterministicToolResponse);
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
            StreamingRentalAssistant streamingAssistant = selectStreamingAssistant(orchestrationResult);
            runAsyncWithLoginContext(currentLoginUser, currentAuthentication, () -> startNativeStream(
                    emitter,
                    resolvedConversationId,
                    orchestrationResult,
                    streamingAssistant
            ));
        } else {
            RentalAssistant rentalAssistant = selectAssistant(orchestrationResult);
            runAsyncWithLoginContext(currentLoginUser, currentAuthentication, () -> startSyntheticStream(
                    emitter,
                    resolvedConversationId,
                    orchestrationResult,
                    rentalAssistant
            ));
        }
        return emitter;
    }

    private AssistantWorkflowOrchestrator.OrchestrationResult orchestrateQuestion(String question, String conversationId) {
        AssistantTaskState currentState = assistantTaskStateStore.get(conversationId);
        AssistantWorkflowOrchestrator.OrchestrationResult result = assistantWorkflowOrchestrator.orchestrate(
                question,
                conversationId,
                currentState,
                isLoggedIn()
        );
        if (result.orchestrated()) {
            log.info(
                    "Assistant question orchestrated, conversationId={}, intent={}, suggestedTool={}, question={}",
                    conversationId,
                    result.intent(),
                    result.suggestedTool(),
                    question
            );
        }
        return result;
    }

    private AssistantChatResponseVo handleDeterministicOrchestratedTool(String conversationId,
                                                                        AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        if (!requiresToolExecution(orchestrationResult)) {
            return null;
        }

        String suggestedTool = blankToEmpty(orchestrationResult.suggestedTool());
        return switch (suggestedTool) {
            case "getMyAppointments" -> executeDeterministicMyAppointmentsQuery(conversationId);
            case "getMyLeaseAgreements" -> executeDeterministicLeaseQuery(conversationId);
            case "searchRooms" -> executeDeterministicRoomSearch(conversationId, orchestrationResult);
            case "getRoomDetail" -> executeDeterministicRoomDetail(conversationId, orchestrationResult);
            case "getRoomDetailByKeyword" -> executeDeterministicRoomDetailByKeyword(conversationId, orchestrationResult);
            default -> null;
        };
    }

    private AssistantChatResponseVo executeDeterministicMyAppointmentsQuery(String conversationId) {
        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState("APPOINTMENT_QUERY", "NEEDS_LOGIN", null, null, null, null, List.of());
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能查询预约记录。请先登录后再试。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }
        return buildDeterministicToolResponse(conversationId, "getMyAppointments", Map.of(), rentalAssistantTools.getMyAppointments());
    }

    private AssistantChatResponseVo executeDeterministicLeaseQuery(String conversationId) {
        if (!isLoggedIn()) {
            AssistantTaskState nextState = new AssistantTaskState("LEASE_QUERY", "NEEDS_LOGIN", null, null, null, null, List.of());
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
                    conversationId,
                    "当前你还没有登录，暂时不能查询租约记录。请先登录后再试。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }
        return buildDeterministicToolResponse(conversationId, "getMyLeaseAgreements", Map.of(), rentalAssistantTools.getMyLeaseAgreements());
    }

    private AssistantChatResponseVo executeDeterministicRoomSearch(String conversationId,
                                                                   AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        RoomSearchCriteria criteria = extractRoomSearchCriteria(orchestrationResult);
        LinkedHashMap<String, Object> toolArguments = new LinkedHashMap<>();
        toolArguments.put("districtName", criteria.regionKeyword());
        toolArguments.put("minRent", criteria.minRent());
        toolArguments.put("maxRent", criteria.maxRent());
        return buildDeterministicToolResponse(
                conversationId,
                "searchRooms",
                toolArguments,
                rentalAssistantTools.searchRooms(criteria.regionKeyword(), criteria.minRent(), criteria.maxRent())
        );
    }

    private AssistantChatResponseVo executeDeterministicRoomDetail(String conversationId,
                                                                   AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        AssistantTaskState currentState = assistantTaskStateStore.get(conversationId);
        if (currentState == null || currentState.selectedRoomId() == null) {
            AssistantTaskState.RoomCandidate candidate = resolveRoomCandidateForDetailQuestion(
                    extractOriginalQuestion(orchestrationResult),
                    currentState
            );
            if (candidate != null && candidate.roomId() != null) {
                return buildDeterministicToolResponse(
                        conversationId,
                        "getRoomDetail",
                        Map.of("roomId", candidate.roomId()),
                        rentalAssistantTools.getRoomDetail(candidate.roomId())
                );
            }
            return executeDeterministicRoomDetailByKeyword(conversationId, orchestrationResult);
        }
        return buildDeterministicToolResponse(
                conversationId,
                "getRoomDetail",
                Map.of("roomId", currentState.selectedRoomId()),
                rentalAssistantTools.getRoomDetail(currentState.selectedRoomId())
        );
    }

    private AssistantChatResponseVo executeDeterministicRoomDetailByKeyword(String conversationId,
                                                                            AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        String roomKeyword = extractRewrittenUserMessage(orchestrationResult);
        if (!StringUtils.hasText(roomKeyword)) {
            roomKeyword = extractOriginalQuestion(orchestrationResult);
        }
        if (!StringUtils.hasText(roomKeyword)) {
            return null;
        }
        return buildDeterministicToolResponse(
                conversationId,
                "getRoomDetailByKeyword",
                Map.of("roomKeyword", roomKeyword),
                rentalAssistantTools.getRoomDetailByKeyword(roomKeyword)
        );
    }

    private AssistantChatResponseVo buildDeterministicToolResponse(String conversationId,
                                                                   String toolName,
                                                                   Map<String, Object> toolArguments,
                                                                   String toolResult) {
        JsonNode resultNode = parseToolResult(toolResult);
        AssistantTaskState previousState = assistantTaskStateStore.get(conversationId);
        AssistantTaskState nextState = buildTaskStateFromToolResult(toolName, resultNode, previousState);
        if (nextState != null) {
            assistantTaskStateStore.save(conversationId, nextState);
        }
        AssistantTaskState responseState = nextState == null ? previousState : nextState;

        AssistantToolExecutionVo toolExecution = new AssistantToolExecutionVo(
                toolName,
                toToolArgumentsJson(toolArguments),
                false,
                summarizeToolResult(toolResult)
        );
        return buildLocalResponse(
                conversationId,
                buildDeterministicToolReply(toolName, resultNode),
                "tool",
                List.of(toolExecution),
                List.of(),
                responseState
        );
    }

    private String buildDeterministicToolReply(String toolName, JsonNode resultNode) {
        return switch (toolName) {
            case "getMyAppointments" -> buildAppointmentQueryReply(resultNode);
            case "getMyLeaseAgreements" -> buildLeaseQueryReply(resultNode);
            case "searchRooms" -> buildRoomSearchReply(resultNode);
            case "getRoomDetail", "getRoomDetailByKeyword" -> buildRoomDetailReply(resultNode);
            default -> StringUtils.hasText(getTextValue(resultNode, "summary"))
                    ? getTextValue(resultNode, "summary")
                    : DEFAULT_EMPTY_REPLY;
        };
    }

    private String buildAppointmentQueryReply(JsonNode resultNode) {
        String summary = getTextValue(resultNode, "summary");
        JsonNode itemsNode = resultNode == null ? null : resultNode.path("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            return StringUtils.hasText(summary) ? summary : "当前没有预约记录。";
        }

        List<String> lines = new ArrayList<>();
        lines.add(StringUtils.hasText(summary) ? summary : "已为你查到预约记录。");
        int limit = Math.min(itemsNode.size(), 5);
        for (int i = 0; i < limit; i++) {
            JsonNode itemNode = itemsNode.get(i);
            String itemText = buildAppointmentLabel(
                    getLongValue(itemNode, "appointmentId"),
                    getTextValue(itemNode, "apartmentName"),
                    getTextValue(itemNode, "appointmentTime")
            );
            String statusText = getTextValue(itemNode, "statusText");
            lines.add((i + 1) + ". " + (StringUtils.hasText(statusText) ? itemText + " / " + statusText : itemText));
        }
        if (itemsNode.size() > limit) {
            lines.add("还有 " + (itemsNode.size() - limit) + " 条记录未展开。");
        }
        return String.join("\n", lines);
    }

    private String buildLeaseQueryReply(JsonNode resultNode) {
        String summary = getTextValue(resultNode, "summary");
        JsonNode itemsNode = resultNode == null ? null : resultNode.path("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            return StringUtils.hasText(summary) ? summary : "当前没有租约记录。";
        }

        List<String> lines = new ArrayList<>();
        lines.add(StringUtils.hasText(summary) ? summary : "已为你查到租约记录。");
        int limit = Math.min(itemsNode.size(), 5);
        for (int i = 0; i < limit; i++) {
            JsonNode itemNode = itemsNode.get(i);
            List<String> parts = new ArrayList<>();
            appendIfPresent(parts, getTextValue(itemNode, "apartmentName"));
            appendIfPresent(parts, getTextValue(itemNode, "roomNumber"));
            appendIfPresent(parts, getTextValue(itemNode, "rentText"));
            appendIfPresent(parts, getTextValue(itemNode, "leaseStatusText"));
            appendIfPresent(parts, getTextValue(itemNode, "leasePeriod"));
            lines.add((i + 1) + ". " + (parts.isEmpty() ? "租约信息待确认" : String.join(" / ", parts)));
        }
        if (itemsNode.size() > limit) {
            lines.add("还有 " + (itemsNode.size() - limit) + " 条记录未展开。");
        }
        return String.join("\n", lines);
    }

    private String buildRoomSearchReply(JsonNode resultNode) {
        String summary = getTextValue(resultNode, "summary");
        JsonNode itemsNode = resultNode == null ? null : resultNode.path("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            return StringUtils.hasText(summary) ? summary : "没有查到符合条件的房源。";
        }

        List<String> lines = new ArrayList<>();
        lines.add(StringUtils.hasText(summary) ? summary : "已为你查到符合条件的房源。");
        int limit = Math.min(itemsNode.size(), 5);
        for (int i = 0; i < limit; i++) {
            JsonNode itemNode = itemsNode.get(i);
            List<String> parts = new ArrayList<>();
            appendIfPresent(parts, getTextValue(itemNode, "title"));
            appendIfPresent(parts, getTextValue(itemNode, "rentText"));
            appendIfPresent(parts, getTextValue(itemNode, "locationText"));
            String labels = joinTextArray(itemNode == null ? null : itemNode.path("labels"));
            appendIfPresent(parts, StringUtils.hasText(labels) ? "标签：" + labels : null);
            lines.add((i + 1) + ". " + (parts.isEmpty() ? "房源信息待确认" : String.join(" / ", parts)));
        }
        if (itemsNode.size() > limit) {
            lines.add("还有 " + (itemsNode.size() - limit) + " 套房源未展开。");
        }
        return String.join("\n", lines);
    }

    private String buildRoomDetailReply(JsonNode resultNode) {
        String summary = getTextValue(resultNode, "summary");
        if (resultNode == null || resultNode.isMissingNode()) {
            return StringUtils.hasText(summary) ? summary : "没有查到对应房间。";
        }

        List<String> lines = new ArrayList<>();
        lines.add(StringUtils.hasText(summary) ? summary : "已为你查到房间详情。");
        appendDetailLine(lines, "房间", getTextValue(resultNode, "title"));
        appendDetailLine(lines, "租金", getTextValue(resultNode, "rentText"));
        appendDetailLine(lines, "位置", getTextValue(resultNode, "locationText"));
        String labels = joinTextArray(resultNode.path("labels"));
        appendDetailLine(lines, "标签", labels);
        String paymentTypes = joinTextArray(resultNode.path("paymentTypes"));
        appendDetailLine(lines, "付款方式", paymentTypes);
        String leaseTerms = joinTextArray(resultNode.path("leaseTerms"));
        appendDetailLine(lines, "可选租期", leaseTerms);
        return String.join("\n", lines);
    }

    private void appendDetailLine(List<String> lines, String label, String value) {
        if (StringUtils.hasText(value)) {
            lines.add("- " + label + "：" + value);
        }
    }

    private void appendIfPresent(List<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value.trim());
        }
    }

    private String joinTextArray(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            if (node == null) {
                continue;
            }
            String value = node.asText(null);
            if (StringUtils.hasText(value)) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? null : String.join("、", values);
    }

    private String toToolArgumentsJson(Map<String, Object> toolArguments) {
        try {
            return objectMapper.writeValueAsString(toolArguments == null ? Map.of() : toolArguments);
        } catch (Exception e) {
            return String.valueOf(toolArguments);
        }
    }

    private RoomSearchCriteria extractRoomSearchCriteria(AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        String searchText = extractRewrittenUserMessage(orchestrationResult);
        if (!StringUtils.hasText(searchText)) {
            searchText = extractOriginalQuestion(orchestrationResult);
        }
        String normalized = normalizeSearchText(searchText);

        BigDecimal minRent = null;
        BigDecimal maxRent = null;
        Matcher rangeMatcher = RENT_RANGE_PATTERN.matcher(normalized);
        if (rangeMatcher.find()) {
            minRent = parseBigDecimal(rangeMatcher.group(1));
            maxRent = parseBigDecimal(rangeMatcher.group(2));
        } else {
            Matcher maxMatcher = MAX_RENT_PATTERN.matcher(normalized);
            if (maxMatcher.find()) {
                maxRent = parseBigDecimal(maxMatcher.group(1));
            }
            Matcher minMatcher = MIN_RENT_PATTERN.matcher(normalized);
            if (minMatcher.find()) {
                minRent = parseBigDecimal(minMatcher.group(1));
            }
        }

        return new RoomSearchCriteria(extractRegionKeyword(normalized), minRent, maxRent);
    }

    private String normalizeSearchText(String searchText) {
        String normalized = blankToEmpty(searchText)
                .replace('，', ' ')
                .replace(',', ' ')
                .trim();
        return normalized
                .replaceFirst("^(帮我|麻烦|请|我想|想|我要)?(查一下|查一查|查查|查询一下|查询|搜索一下|搜索|搜一下|搜|找一下|找找)?", "")
                .trim();
    }

    private BigDecimal parseBigDecimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String extractRegionKeyword(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }

        Matcher matcher = REGION_PATTERN.matcher(question);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            String normalized = candidate.trim();
            if (normalized.contains("房源")
                    || normalized.contains("预约")
                    || normalized.contains("租约")
                    || normalized.contains("记录")) {
                continue;
            }
            return normalized;
        }

        if (question.contains("北京")) {
            return "北京市";
        }
        if (question.contains("上海")) {
            return "上海市";
        }
        if (question.contains("广州")) {
            return "广州市";
        }
        if (question.contains("深圳")) {
            return "深圳市";
        }
        return null;
    }

    private AssistantTaskState.RoomCandidate resolveRoomCandidateForDetailQuestion(String question,
                                                                                   AssistantTaskState currentState) {
        if (currentState == null || currentState.candidateRooms() == null || currentState.candidateRooms().isEmpty()) {
            return null;
        }

        Integer candidateIndex = extractRoomCandidateIndex(question);
        if (candidateIndex != null && candidateIndex > 0 && candidateIndex <= currentState.candidateRooms().size()) {
            return currentState.candidateRooms().get(candidateIndex - 1);
        }

        String normalizedQuestion = normalizeRoomReference(question);
        if (!StringUtils.hasText(normalizedQuestion)) {
            return null;
        }

        AssistantTaskState.RoomCandidate bestMatch = null;
        int bestScore = 0;
        for (AssistantTaskState.RoomCandidate candidate : currentState.candidateRooms()) {
            String normalizedTitle = normalizeRoomReference(candidate.title());
            if (!StringUtils.hasText(normalizedTitle)) {
                continue;
            }
            int score = scoreRoomCandidate(normalizedQuestion, normalizedTitle);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = candidate;
            }
        }
        return bestScore > 0 ? bestMatch : null;
    }

    private Integer extractRoomCandidateIndex(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        Matcher matcher = ROOM_INDEX_PATTERN.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    private int scoreRoomCandidate(String normalizedQuestion, String normalizedTitle) {
        if (normalizedQuestion.contains(normalizedTitle)) {
            return normalizedTitle.length() + 10;
        }
        if (normalizedTitle.contains(normalizedQuestion)) {
            return normalizedQuestion.length();
        }
        String questionDigits = extractDigits(normalizedQuestion);
        String titleDigits = extractDigits(normalizedTitle);
        if (StringUtils.hasText(questionDigits) && questionDigits.equals(titleDigits)) {
            return 5;
        }
        return 0;
    }

    private String normalizeRoomReference(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .replace("介绍介绍", "")
                .replace("介绍一下", "")
                .replace("介绍", "")
                .replace("详情", "")
                .replace("看一下", "")
                .replace("看下", "")
                .replace("看看", "")
                .replace("这个房源", "")
                .replace("这个", "")
                .replace("房源", "")
                .replace("房间", "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String extractDigits(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            }
        }
        return digits.toString();
    }

    private String extractRewrittenUserMessage(AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        JsonNode rawDecisionNode = parseToolResult(orchestrationResult == null ? null : orchestrationResult.rawDecision());
        return getTextValue(rawDecisionNode, "rewrittenUserMessage");
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

    private RentalAssistant selectAssistant(AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        if (requiresToolExecution(orchestrationResult)) {
            ToolFirstRentalAssistant toolFirstAssistant = toolFirstRentalAssistantProvider.getIfAvailable();
            if (toolFirstAssistant != null) {
                return toolFirstAssistant::chat;
            }
            log.warn("Tool-first assistant unavailable, falling back to default assistant");
        }
        return requireAssistant();
    }

    private StreamingRentalAssistant selectStreamingAssistant(AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        if (requiresToolExecution(orchestrationResult)) {
            StreamingToolFirstRentalAssistant toolFirstAssistant = streamingToolFirstRentalAssistantProvider.getIfAvailable();
            if (toolFirstAssistant != null) {
                return toolFirstAssistant::chat;
            }
            log.warn("Streaming tool-first assistant unavailable, falling back to default streaming assistant");
        }
        return streamingRentalAssistantProvider.getIfAvailable();
    }

    private void startSyntheticStream(SseEmitter emitter,
                                      String conversationId,
                                      AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult,
                                      RentalAssistant rentalAssistant) {
        try {
            Result<String> assistantResult = invokeAssistantWithRecovery(rentalAssistant, conversationId, orchestrationResult);
            AssistantChatResponseVo response = buildResponse(conversationId, assistantResult, orchestrationResult);
            emitSyntheticResponse(emitter, extractOriginalQuestion(orchestrationResult), response);
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
                                   AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult,
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
            TokenStream tokenStream = streamingRentalAssistant.chat(conversationId, orchestrationResult.question());
            startTokenStream(emitter, conversationId, orchestrationResult, tokenStream);
        } catch (Exception e) {
            emitErrorAndComplete(emitter, e, conversationId);
        }
    }

    private void startTokenStream(SseEmitter emitter,
                                  String conversationId,
                                  AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult,
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
                        orchestrationResult,
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
                                      AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult,
                                      StringBuilder contentBuilder,
                                      List<AssistantToolExecutionVo> toolExecutions,
                                      List<ToolExecution> rawToolExecutions,
                                      ChatResponse chatResponse) {
        AiMessage aiMessage = chatResponse == null ? null : chatResponse.aiMessage();
        String reply = aiMessage == null ? null : aiMessage.text();
        if (!StringUtils.hasText(reply)) {
            reply = contentBuilder.toString();
        }

        enforceToolExecutionIfRequired(orchestrationResult, toolExecutions);

        String formattedReply = formatReply(reply);
        AssistantTaskState taskState = resolveTaskState(conversationId, rawToolExecutions, false);
        AssistantChatResponseVo response = new AssistantChatResponseVo(
                conversationId,
                formattedReply,
                splitParagraphs(formattedReply),
                resolveAnswerSource(toolExecutions, List.of()),
                chatResponse == null || chatResponse.finishReason() == null
                        ? "unknown"
                        : chatResponse.finishReason().name().toLowerCase(Locale.ROOT),
                List.copyOf(toolExecutions),
                List.of(),
                toTaskStateVo(taskState),
                buildNextActions(taskState)
        );
        sendEvent(emitter, "complete", buildCompletePayload(response));
        emitter.complete();
        logAssistantSuccess(extractOriginalQuestion(orchestrationResult), response);
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
        AppointmentActionDecision actionDecision = analyzeAppointmentAction(question, currentState);
        boolean confirmationFlow = "APPOINTMENT_CONFIRMING".equals(currentState.taskType());
        boolean cancelConfirmationFlow = "APPOINTMENT_CANCEL_CONFIRMING".equals(currentState.taskType());
        boolean rescheduleConfirmationFlow = "APPOINTMENT_RESCHEDULE_CONFIRMING".equals(currentState.taskType());
        boolean availabilityQuestion = isAppointmentAvailabilityQuestion(question, currentState);
        boolean appointmentIntent = actionDecision.isCreate() || isRoomAppointmentIntent(question, currentState)
                || ("APPOINTMENT_INTENT".equals(currentState.taskType()) && parsedAppointmentTime != null);
        boolean appointmentCancelIntent = actionDecision.isCancel() || isAppointmentCancelIntent(question, currentState);
        boolean appointmentRescheduleIntent = actionDecision.isReschedule() || isAppointmentRescheduleIntent(question, currentState)
                || ("APPOINTMENT_RESCHEDULE_INTENT".equals(currentState.taskType()) && parsedAppointmentTime != null);
        log.info(
                "Assistant agent decision, conversationId={}, taskType={}, taskStatus={}, loggedIn={}, confirmationFlow={}, cancelConfirmationFlow={}, rescheduleConfirmationFlow={}, availabilityQuestion={}, appointmentIntent={}, appointmentCancelIntent={}, appointmentRescheduleIntent={}, llmAction={}, llmAppointmentId={}, llmRoomId={}, llmTimeText={}, question={}",
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
                actionDecision.action(),
                actionDecision.appointmentId(),
                actionDecision.roomId(),
                actionDecision.timeText(),
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
            return handleAppointmentIntent(question, conversationId, currentState, actionDecision);
        }

        if (appointmentCancelIntent) {
            return handleAppointmentCancelIntent(question, conversationId, currentState, actionDecision);
        }

        if (appointmentRescheduleIntent) {
            return handleAppointmentRescheduleIntent(question, conversationId, currentState, actionDecision);
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
                currentState == null ? List.of() : safeCandidateRooms(currentState)
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

    private AppointmentActionDecision analyzeAppointmentAction(String question, AssistantTaskState currentState) {
        if (!shouldAnalyzeAppointmentAction(question, currentState)) {
            return AppointmentActionDecision.none();
        }

        AppointmentActionAnalyzer analyzer = appointmentActionAnalyzerProvider.getIfAvailable();
        if (analyzer == null) {
            return AppointmentActionDecision.none();
        }

        try {
            String raw = analyzer.analyze(buildAppointmentActionAnalyzerInput(question, currentState));
            AppointmentActionDecision decision = parseAppointmentActionDecision(raw);
            return decision == null ? AppointmentActionDecision.none() : decision;
        } catch (Exception e) {
            log.warn("Failed to analyze appointment action with LLM", e);
            return AppointmentActionDecision.none();
        }
    }

    private boolean shouldAnalyzeAppointmentAction(String question, AssistantTaskState currentState) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        if (looksLikeAppointmentManagementQuestion(question)) {
            return true;
        }
        if (currentState == null || !StringUtils.hasText(currentState.taskType())) {
            return false;
        }
        return List.of(
                "ROOM_DETAIL",
                "APPOINTMENT_INTENT",
                "APPOINTMENT_CONFIRMING",
                "APPOINTMENT_QUERY",
                "APPOINTMENT_CREATED",
                "APPOINTMENT_CANCEL_CONFIRMING",
                "APPOINTMENT_RESCHEDULE_INTENT",
                "APPOINTMENT_RESCHEDULE_CONFIRMING",
                "APPOINTMENT_RESCHEDULED"
        ).contains(currentState.taskType());
    }

    private String buildAppointmentActionAnalyzerInput(String question, AssistantTaskState currentState) {
        AppointmentQueryContext appointmentQueryContext = isLoggedIn()
                ? loadAppointmentQueryContext()
                : new AppointmentQueryContext(List.of());
        StringBuilder appointmentSection = new StringBuilder();
        List<AppointmentSelection> waitingAppointments = appointmentQueryContext.waitingAppointments();
        if (waitingAppointments == null || waitingAppointments.isEmpty()) {
            appointmentSection.append("none");
        } else {
            for (int i = 0; i < waitingAppointments.size(); i++) {
                AppointmentSelection selection = waitingAppointments.get(i);
                appointmentSection.append(i + 1)
                        .append(". ")
                        .append(selection.label())
                        .append(System.lineSeparator());
            }
        }

        return """
                now=%s
                timezone=%s
                loggedIn=%s
                currentTaskType=%s
                currentTaskStatus=%s
                selectedRoomId=%s
                selectedRoomTitle=%s
                selectedAppointmentId=%s
                selectedAppointmentLabel=%s
                proposedAppointmentTime=%s
                waitingAppointments=
                %s
                userMessage=%s
                """.formatted(
                java.time.ZonedDateTime.now(ZONE_ID),
                ZONE_ID,
                isLoggedIn(),
                currentState == null ? "" : blankToEmpty(currentState.taskType()),
                currentState == null ? "" : blankToEmpty(currentState.taskStatus()),
                currentState == null || currentState.selectedRoomId() == null ? "" : currentState.selectedRoomId(),
                currentState == null ? "" : blankToEmpty(currentState.selectedRoomTitle()),
                currentState == null || currentState.selectedAppointmentId() == null ? "" : currentState.selectedAppointmentId(),
                currentState == null ? "" : blankToEmpty(currentState.selectedAppointmentLabel()),
                currentState == null ? "" : blankToEmpty(currentState.proposedAppointmentTime()),
                appointmentSection,
                question
        );
    }

    private AppointmentActionDecision parseAppointmentActionDecision(String raw) {
        if (!StringUtils.hasText(raw)) {
            return AppointmentActionDecision.none();
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        JsonNode node = parseToolResult(cleaned);
        if (node == null || !node.isObject()) {
            return AppointmentActionDecision.none();
        }
        return new AppointmentActionDecision(
                getTextValue(node, "action"),
                getLongValue(node, "appointmentId"),
                getLongValue(node, "roomId"),
                getTextValue(node, "timeText"),
                node.path("needsSchedule").asBoolean(false),
                node.path("needsAppointmentSelection").asBoolean(false)
        );
    }

    private AssistantChatResponseVo handleAppointmentIntent(String question,
                                                            String conversationId,
                                                            AssistantTaskState currentState,
                                                            AppointmentActionDecision actionDecision) {
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

        AppointmentTimeParser.ParsedAppointmentTime parsedAppointmentTime = parseAppointmentTime(actionDecision.timeText(), question);
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
                                                                  AssistantTaskState currentState,
                                                                  AppointmentActionDecision actionDecision) {
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

        AppointmentSelection appointmentSelection = resolveAppointmentSelection(actionDecision, question, currentState);
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

    private AssistantChatResponseVo handleAppointmentRescheduleIntent(String question,
                                                                      String conversationId,
                                                                      AssistantTaskState currentState,
                                                                      AppointmentActionDecision actionDecision) {
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
                    "当前你还没有登录，暂时不能改约。请先登录后再试。",
                    "agent",
                    List.of(),
                    List.of(),
                    nextState
            );
        }

        AppointmentSelection appointmentSelection = resolveAppointmentSelection(actionDecision, question, currentState);
        if (appointmentSelection == null || appointmentSelection.appointmentId() == null) {
            return buildLocalResponse(
                    conversationId,
                    "我还没确定你想修改哪一条预约。你可以直接说“把预约13改到明天下午”或“改约最新预约”。",
                    "agent",
                    List.of(),
                    List.of(),
                    currentState
            );
        }

        AppointmentTimeParser.ParsedAppointmentTime parsedAppointmentTime = parseAppointmentTime(actionDecision.timeText(), question);
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
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
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
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
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
                    safeCandidateRooms(currentState)
            );
            assistantTaskStateStore.save(conversationId, nextState);
            return buildLocalResponse(
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
                safeCandidateRooms(currentState)
        );
        assistantTaskStateStore.save(conversationId, nextState);
        String reply = "我准备把 **%s** 改约到 **%s**。如果确认修改，请回复“确认”；如果想换个时间，直接告诉我新的预约时间即可。"
                .formatted(appointmentSelection.label(), appointmentTimeText);
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

    private AssistantChatResponseVo rescheduleAppointmentFromState(String conversationId, AssistantTaskState currentState) {
        String toolArguments = "{\"appointmentId\":%d,\"appointmentTime\":\"%s\"}".formatted(
                currentState.selectedAppointmentId(),
                currentState.proposedAppointmentTime() == null ? "" : currentState.proposedAppointmentTime()
        );
        String toolResult = rentalAssistantTools.rescheduleAppointment(
                currentState.selectedAppointmentId(),
                currentState.proposedAppointmentTime()
        );
        JsonNode resultNode = parseToolResult(toolResult);
        Integer appointmentStatusCode = resultNode != null && resultNode.hasNonNull("appointmentStatusCode")
                ? resultNode.get("appointmentStatusCode").asInt()
                : null;
        boolean success = resultNode != null
                && resultNode.hasNonNull("appointmentId")
                && appointmentStatusCode != null
                && appointmentStatusCode == 1
                && StringUtils.hasText(getTextValue(resultNode, "appointmentTime"));

        AssistantToolExecutionVo toolExecution = new AssistantToolExecutionVo(
                "rescheduleAppointment",
                toolArguments,
                !success,
                summarizeToolResult(toolResult)
        );

        String appointmentLabel = currentState.selectedAppointmentLabel();
        String appointmentTime = currentState.proposedAppointmentTime();
        if (resultNode != null) {
            String apartmentName = getTextValue(resultNode, "apartmentName");
            String updatedAppointmentTime = getTextValue(resultNode, "appointmentTime");
            if (StringUtils.hasText(updatedAppointmentTime)) {
                appointmentTime = updatedAppointmentTime;
            }
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
                "APPOINTMENT_RESCHEDULED",
                "COMPLETED",
                currentState.selectedRoomId(),
                currentState.selectedRoomTitle(),
                currentState.selectedApartmentId(),
                currentState.selectedAppointmentId(),
                appointmentLabel,
                appointmentTime,
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
                : success ? "已为你修改预约时间。" : "当前没有成功修改预约时间，请稍后再试。";
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

    private AppointmentSelection resolveAppointmentSelection(AppointmentActionDecision actionDecision,
                                                             String question,
                                                             AssistantTaskState currentState) {
        if (actionDecision != null && actionDecision.appointmentId() != null) {
            if (currentState != null
                    && actionDecision.appointmentId().equals(currentState.selectedAppointmentId())
                    && StringUtils.hasText(currentState.selectedAppointmentLabel())) {
                return new AppointmentSelection(actionDecision.appointmentId(), currentState.selectedAppointmentLabel());
            }

            AppointmentQueryContext appointmentQueryContext = isLoggedIn()
                    ? loadAppointmentQueryContext()
                    : new AppointmentQueryContext(List.of());
            AppointmentSelection selectionFromList = appointmentQueryContext.waitingAppointments().stream()
                    .filter(item -> actionDecision.appointmentId().equals(item.appointmentId()))
                    .findFirst()
                    .orElse(null);
            if (selectionFromList != null) {
                return selectionFromList;
            }
            return new AppointmentSelection(actionDecision.appointmentId(), "预约ID " + actionDecision.appointmentId());
        }
        return resolveAppointmentSelection(question, currentState);
    }

    private AppointmentTimeParser.ParsedAppointmentTime parseAppointmentTime(String preferredTimeText, String fallbackQuestion) {
        AppointmentTimeParser.ParsedAppointmentTime parsed = AppointmentTimeParser.parse(preferredTimeText, ZONE_ID);
        if (parsed != null) {
            return parsed;
        }
        return AppointmentTimeParser.parse(fallbackQuestion, ZONE_ID);
    }

    private AppointmentQueryContext loadAppointmentQueryContext() {
        try {
            JsonNode resultNode = parseToolResult(rentalAssistantTools.getMyAppointments());
            if (resultNode == null) {
                return new AppointmentQueryContext(List.of());
            }

            JsonNode itemsNode = resultNode.path("items");
            if (!itemsNode.isArray()) {
                return new AppointmentQueryContext(List.of());
            }

            List<AppointmentSelection> waitingAppointments = new ArrayList<>();
            for (JsonNode itemNode : itemsNode) {
                Long appointmentId = getLongValue(itemNode, "appointmentId");
                Integer appointmentStatusCode = itemNode.hasNonNull("appointmentStatusCode")
                        ? itemNode.get("appointmentStatusCode").asInt()
                        : null;
                if (appointmentId == null || appointmentStatusCode == null || appointmentStatusCode != 1) {
                    continue;
                }
                waitingAppointments.add(new AppointmentSelection(
                        appointmentId,
                        buildAppointmentLabel(
                                appointmentId,
                                getTextValue(itemNode, "apartmentName"),
                                getTextValue(itemNode, "appointmentTime")
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

    private List<AssistantTaskState.RoomCandidate> safeCandidateRooms(AssistantTaskState currentState) {
        return currentState == null || currentState.candidateRooms() == null
                ? List.of()
                : currentState.candidateRooms();
    }

    private Result<String> invokeAssistantWithRecovery(RentalAssistant rentalAssistant,
                                                       String conversationId,
                                                       AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        try {
            Result<String> assistantResult = rentalAssistant.chat(conversationId, orchestrationResult.question());
            enforceToolExecutionIfRequired(orchestrationResult, assistantResult);
            return assistantResult;
        } catch (Exception firstException) {
            if (!isBrokenToolCallMemory(firstException) || !clearConversationMemory(conversationId)) {
                throw firstException;
            }

            log.warn("Assistant conversation memory was inconsistent and has been cleared, conversationId={}", conversationId, firstException);
            Result<String> assistantResult = rentalAssistant.chat(conversationId, orchestrationResult.question());
            enforceToolExecutionIfRequired(orchestrationResult, assistantResult);
            return assistantResult;
        }
    }

    private AssistantChatResponseVo buildResponse(String conversationId,
                                                 Result<String> assistantResult,
                                                 AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
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

    private void enforceToolExecutionIfRequired(AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult,
                                                Result<String> assistantResult) {
        if (!requiresToolExecution(orchestrationResult)) {
            return;
        }
        if (assistantResult != null
                && assistantResult.toolExecutions() != null
                && !assistantResult.toolExecutions().isEmpty()) {
            return;
        }
        log.warn(
                "Assistant required tool execution but none occurred, intent={}, suggestedTool={}, originalQuestion={}",
                orchestrationResult == null ? "" : orchestrationResult.intent(),
                orchestrationResult == null ? "" : orchestrationResult.suggestedTool(),
                extractOriginalQuestion(orchestrationResult)
        );
        throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "本次业务请求没有触发平台工具调用，请稍后重试");
    }

    private void enforceToolExecutionIfRequired(AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult,
                                                List<AssistantToolExecutionVo> toolExecutions) {
        if (!requiresToolExecution(orchestrationResult)) {
            return;
        }
        if (toolExecutions != null && !toolExecutions.isEmpty()) {
            return;
        }
        log.warn(
                "Assistant required tool execution but none occurred in streaming mode, intent={}, suggestedTool={}, originalQuestion={}",
                orchestrationResult == null ? "" : orchestrationResult.intent(),
                orchestrationResult == null ? "" : orchestrationResult.suggestedTool(),
                extractOriginalQuestion(orchestrationResult)
        );
        throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "本次业务请求没有触发平台工具调用，请稍后重试");
    }

    private boolean requiresToolExecution(AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        return orchestrationResult != null && orchestrationResult.orchestrated();
    }

    private String extractOriginalQuestion(AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        if (orchestrationResult == null) {
            return "";
        }
        if (StringUtils.hasText(orchestrationResult.originalQuestion())) {
            return orchestrationResult.originalQuestion();
        }
        return blankToEmpty(orchestrationResult.question());
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
        AssistantTaskState nextState = deriveTaskState(
                assistantResult == null ? null : assistantResult.toolExecutions(),
                assistantResult != null && assistantResult.sources() != null && !assistantResult.sources().isEmpty(),
                previousState
        );
        if (nextState != null) {
            assistantTaskStateStore.save(conversationId, nextState);
            return nextState;
        }
        return previousState;
    }

    private AssistantTaskState resolveTaskState(String conversationId,
                                                List<ToolExecution> toolExecutions,
                                                boolean hasKnowledgeSources) {
        AssistantTaskState previousState = assistantTaskStateStore.get(conversationId);
        AssistantTaskState nextState = deriveTaskState(toolExecutions, hasKnowledgeSources, previousState);
        if (nextState != null) {
            assistantTaskStateStore.save(conversationId, nextState);
            return nextState;
        }
        return previousState;
    }

    private AssistantTaskState deriveTaskState(List<ToolExecution> toolExecutions,
                                               boolean hasKnowledgeSources,
                                               AssistantTaskState previousState) {
        AssistantTaskState currentState = previousState;
        boolean stateUpdated = false;
        if (toolExecutions != null) {
            for (ToolExecution toolExecution : toolExecutions) {
                AssistantTaskState derivedState = buildTaskStateFromToolExecution(toolExecution, currentState);
                if (derivedState != null) {
                    currentState = derivedState;
                    stateUpdated = true;
                }
            }
        }
        if (stateUpdated) {
            return currentState;
        }
        if (hasKnowledgeSources) {
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
        return buildTaskStateFromToolResult(toolName, resultNode, previousState);
    }

    private AssistantTaskState buildTaskStateFromToolResult(String toolName, JsonNode resultNode, AssistantTaskState previousState) {
        if (resultNode == null) {
            return previousState;
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

        if ("rescheduleAppointment".equals(toolName)) {
            Long appointmentId = getLongValue(resultNode, "appointmentId");
            String apartmentName = getTextValue(resultNode, "apartmentName");
            String appointmentTime = getTextValue(resultNode, "appointmentTime");
            return new AssistantTaskState(
                    "APPOINTMENT_RESCHEDULED",
                    "COMPLETED",
                    previousState == null ? null : previousState.selectedRoomId(),
                    previousState == null ? null : previousState.selectedRoomTitle(),
                    getLongValue(resultNode, "apartmentId"),
                    appointmentId,
                    buildAppointmentLabel(appointmentId, apartmentName, appointmentTime),
                    appointmentTime,
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
                    new AssistantNextActionVo("RESCHEDULE_CURRENT_APPOINTMENT", "修改这次预约时间", "把刚刚的预约改到明天下午3点", null, true),
                    new AssistantNextActionVo("CANCEL_CURRENT_APPOINTMENT", "取消这次预约", "取消刚刚的预约", null, true),
                    new AssistantNextActionVo("SEARCH_MORE_ROOMS", "继续看看其他房源", "再给我推荐几套房源", null, false)
            );
        }
        if ("APPOINTMENT_QUERY".equals(taskState.taskType()) && taskState.selectedAppointmentId() != null) {
            return List.of(
                    new AssistantNextActionVo("CANCEL_LATEST_APPOINTMENT", "取消最新预约", "取消最新预约", null, true),
                    new AssistantNextActionVo("RESCHEDULE_LATEST_APPOINTMENT", "修改最新预约时间", "把最新预约改到明天下午3点", null, true),
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
        if ("APPOINTMENT_RESCHEDULE_INTENT".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("RESCHEDULE_TOMORROW_AFTERNOON", "改到明天下午", "把这条预约改到明天下午", taskState.selectedAppointmentId(), false),
                    new AssistantNextActionVo("RESCHEDULE_TOMORROW_MORNING", "改到明天上午", "把这条预约改到明天上午", taskState.selectedAppointmentId(), false),
                    new AssistantNextActionVo("VIEW_APPOINTMENTS", "再看一下我的预约", "帮我看一下我的预约", null, false)
            );
        }
        if ("APPOINTMENT_RESCHEDULE_CONFIRMING".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("CONFIRM_RESCHEDULE_APPOINTMENT", "确认改约", "确认", taskState.selectedAppointmentId(), true),
                    new AssistantNextActionVo("KEEP_ORIGINAL_APPOINTMENT_TIME", "先不改了", "保留", taskState.selectedAppointmentId(), false)
            );
        }
        if ("APPOINTMENT_RESCHEDULED".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("VIEW_APPOINTMENTS", "查看我的预约", "帮我看一下我的预约", null, false),
                    new AssistantNextActionVo("CANCEL_CURRENT_APPOINTMENT", "取消这条预约", "取消这条预约", null, true)
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

    private record AppointmentQueryContext(List<AppointmentSelection> waitingAppointments) {
        private AppointmentSelection latestWaiting() {
            return waitingAppointments == null || waitingAppointments.isEmpty() ? null : waitingAppointments.get(0);
        }
    }

    private record AppointmentActionDecision(String action,
                                             Long appointmentId,
                                             Long roomId,
                                             String timeText,
                                             boolean needsSchedule,
                                             boolean needsAppointmentSelection) {
        private static AppointmentActionDecision none() {
            return new AppointmentActionDecision("none", null, null, null, false, false);
        }

        private boolean isCreate() {
            return "create".equalsIgnoreCase(action);
        }

        private boolean isCancel() {
            return "cancel".equalsIgnoreCase(action);
        }

        private boolean isReschedule() {
            return "reschedule".equalsIgnoreCase(action);
        }
    }

    private record AppointmentSelection(Long appointmentId, String label) {
    }

    private record RoomSearchCriteria(String regionKeyword, BigDecimal minRent, BigDecimal maxRent) {
    }
}
