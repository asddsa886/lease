package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskState;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskStateStore;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.dto.AssistantToolExecutionVo;
import com.atguigu.lease.web.app.chat.tool.RentalAssistantTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AssistantDeterministicToolExecutor {

    private static final Pattern REGION_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,12}(?:市|区|县|旗|州|省))");
    private static final Pattern RENT_RANGE_PATTERN = Pattern.compile("(\\d{3,6})(?:\\s*元|块)?\\s*(?:到|-|~|～)\\s*(\\d{3,6})");
    private static final Pattern MAX_RENT_PATTERN = Pattern.compile("(\\d{3,6})(?:\\s*元|块)?\\s*(?:以内|以下|之内|左右|封顶)");
    private static final Pattern MIN_RENT_PATTERN = Pattern.compile("(\\d{3,6})(?:\\s*元|块)?\\s*(?:以上|起|及以上)");
    private static final Pattern ROOM_INDEX_PATTERN = Pattern.compile("第\\s*([1-9]\\d*)\\s*(?:个|套|间)?");

    private final RentalAssistantTools rentalAssistantTools;
    private final AssistantTaskStateStore assistantTaskStateStore;
    private final AssistantConversationSupport conversationSupport;
    private final ObjectMapper objectMapper;

    public AssistantChatResponseVo execute(String conversationId,
                                           AssistantWorkflowOrchestrator.OrchestrationResult orchestrationResult) {
        if (orchestrationResult == null || !orchestrationResult.orchestrated()) {
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
            return conversationSupport.buildLocalResponse(
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
            return conversationSupport.buildLocalResponse(
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
        JsonNode resultNode = conversationSupport.parseToolResult(toolResult);
        AssistantTaskState previousState = assistantTaskStateStore.get(conversationId);
        AssistantTaskState nextState = conversationSupport.buildTaskStateFromToolResult(toolName, resultNode, previousState);
        if (nextState != null) {
            assistantTaskStateStore.save(conversationId, nextState);
        }
        AssistantTaskState responseState = nextState == null ? previousState : nextState;

        AssistantToolExecutionVo toolExecution = new AssistantToolExecutionVo(
                toolName,
                toToolArgumentsJson(toolArguments),
                false,
                conversationSupport.summarizeToolResult(toolResult)
        );
        return conversationSupport.buildLocalResponse(
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
            default -> StringUtils.hasText(conversationSupport.getTextValue(resultNode, "summary"))
                    ? conversationSupport.getTextValue(resultNode, "summary")
                    : "我当前没有生成有效回复，请换个问法再试。";
        };
    }

    private String buildAppointmentQueryReply(JsonNode resultNode) {
        String summary = conversationSupport.getTextValue(resultNode, "summary");
        JsonNode itemsNode = resultNode == null ? null : resultNode.path("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            return StringUtils.hasText(summary) ? summary : "当前没有预约记录。";
        }

        List<String> lines = new ArrayList<>();
        lines.add(StringUtils.hasText(summary) ? summary : "已为你查到预约记录。");
        int limit = Math.min(itemsNode.size(), 5);
        for (int i = 0; i < limit; i++) {
            JsonNode itemNode = itemsNode.get(i);
            String itemText = conversationSupport.buildAppointmentLabel(
                    conversationSupport.getLongValue(itemNode, "appointmentId"),
                    conversationSupport.getTextValue(itemNode, "apartmentName"),
                    conversationSupport.getTextValue(itemNode, "appointmentTime")
            );
            String statusText = conversationSupport.getTextValue(itemNode, "statusText");
            lines.add((i + 1) + ". " + (StringUtils.hasText(statusText) ? itemText + " / " + statusText : itemText));
        }
        if (itemsNode.size() > limit) {
            lines.add("还有 " + (itemsNode.size() - limit) + " 条记录未展开。");
        }
        return String.join("\n", lines);
    }

    private String buildLeaseQueryReply(JsonNode resultNode) {
        String summary = conversationSupport.getTextValue(resultNode, "summary");
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
            appendIfPresent(parts, conversationSupport.getTextValue(itemNode, "apartmentName"));
            appendIfPresent(parts, conversationSupport.getTextValue(itemNode, "roomNumber"));
            appendIfPresent(parts, conversationSupport.getTextValue(itemNode, "rentText"));
            appendIfPresent(parts, conversationSupport.getTextValue(itemNode, "leaseStatusText"));
            appendIfPresent(parts, conversationSupport.getTextValue(itemNode, "leasePeriod"));
            lines.add((i + 1) + ". " + (parts.isEmpty() ? "租约信息待确认" : String.join(" / ", parts)));
        }
        if (itemsNode.size() > limit) {
            lines.add("还有 " + (itemsNode.size() - limit) + " 条记录未展开。");
        }
        return String.join("\n", lines);
    }

    private String buildRoomSearchReply(JsonNode resultNode) {
        String summary = conversationSupport.getTextValue(resultNode, "summary");
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
            appendIfPresent(parts, conversationSupport.getTextValue(itemNode, "title"));
            appendIfPresent(parts, conversationSupport.getTextValue(itemNode, "rentText"));
            appendIfPresent(parts, conversationSupport.getTextValue(itemNode, "locationText"));
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
        String summary = conversationSupport.getTextValue(resultNode, "summary");
        if (resultNode == null || resultNode.isMissingNode()) {
            return StringUtils.hasText(summary) ? summary : "没有查到对应房间。";
        }

        List<String> lines = new ArrayList<>();
        lines.add(StringUtils.hasText(summary) ? summary : "已为你查到房间详情。");
        appendDetailLine(lines, "房间", conversationSupport.getTextValue(resultNode, "title"));
        appendDetailLine(lines, "租金", conversationSupport.getTextValue(resultNode, "rentText"));
        appendDetailLine(lines, "位置", conversationSupport.getTextValue(resultNode, "locationText"));
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
        JsonNode rawDecisionNode = conversationSupport.parseToolResult(orchestrationResult == null ? null : orchestrationResult.rawDecision());
        return conversationSupport.getTextValue(rawDecisionNode, "rewrittenUserMessage");
    }

    private boolean isLoggedIn() {
        return LoginUserHolder.get() != null;
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

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record RoomSearchCriteria(String regionKeyword, BigDecimal minRent, BigDecimal maxRent) {
    }
}
