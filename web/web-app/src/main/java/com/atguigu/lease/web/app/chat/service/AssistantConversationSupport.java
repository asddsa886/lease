package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.web.app.chat.agent.AssistantTaskState;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskStateStore;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.dto.AssistantKnowledgeSourceVo;
import com.atguigu.lease.web.app.chat.dto.AssistantNextActionVo;
import com.atguigu.lease.web.app.chat.dto.AssistantRoomCandidateVo;
import com.atguigu.lease.web.app.chat.dto.AssistantTaskStateVo;
import com.atguigu.lease.web.app.chat.dto.AssistantToolExecutionVo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantConversationSupport {

    private static final String DEFAULT_EMPTY_REPLY = "我当前没有生成有效回复，请换个问法再试。";
    private static final int KNOWLEDGE_PREVIEW_LIMIT = 180;

    private final AssistantTaskStateStore assistantTaskStateStore;
    private final ObjectMapper objectMapper;

    public AssistantChatResponseVo buildAssistantResponse(String conversationId, Result<String> assistantResult) {
        String formattedReply = formatReply(assistantResult == null ? null : assistantResult.content());
        List<String> paragraphs = splitParagraphs(formattedReply);
        List<AssistantToolExecutionVo> toolExecutions = buildToolExecutions(assistantResult);
        List<AssistantKnowledgeSourceVo> knowledgeSources = buildKnowledgeSources(assistantResult);
        AssistantTaskState taskState = resolveTaskState(conversationId, assistantResult);
        return new AssistantChatResponseVo(
                conversationId,
                formattedReply,
                paragraphs,
                resolveAnswerSource(toolExecutions, knowledgeSources),
                resolveFinishReason(assistantResult == null ? null : assistantResult.finishReason()),
                toolExecutions,
                knowledgeSources,
                toTaskStateVo(taskState),
                buildNextActions(taskState)
        );
    }

    public AssistantChatResponseVo buildLocalResponse(String conversationId,
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

    public Map<String, Object> buildCompletePayload(AssistantChatResponseVo response) {
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

    public AssistantTaskState resolveTaskState(String conversationId, Result<String> assistantResult) {
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

    public AssistantTaskState resolveTaskState(String conversationId,
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

    public AssistantTaskState buildTaskStateFromToolResult(String toolName,
                                                           JsonNode resultNode,
                                                           AssistantTaskState previousState) {
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

    public List<AssistantNextActionVo> buildNextActions(AssistantTaskState taskState) {
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
        if ("APPOINTMENT_CANCELED".equals(taskState.taskType())) {
            return List.of(
                    new AssistantNextActionVo("VIEW_APPOINTMENTS", "再看一下我的预约", "帮我看一下我的预约", null, false),
                    new AssistantNextActionVo("SEARCH_MORE_ROOMS", "继续看看其他房源", "再给我推荐几套房源", null, false)
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

    public AssistantTaskStateVo toTaskStateVo(AssistantTaskState taskState) {
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

    public JsonNode parseToolResult(String toolResult) {
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

    public Long getLongValue(JsonNode node, String fieldName) {
        if (node == null || !node.hasNonNull(fieldName)) {
            return null;
        }
        JsonNode valueNode = node.get(fieldName);
        return valueNode.canConvertToLong() ? valueNode.longValue() : null;
    }

    public String getTextValue(JsonNode node, String fieldName) {
        if (node == null || !node.hasNonNull(fieldName)) {
            return null;
        }
        String value = node.get(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public String summarizeToolResult(String toolResult) {
        if (!StringUtils.hasText(toolResult)) {
            return "工具已执行完成";
        }
        String normalized = toolResult.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    public String buildAppointmentLabel(Long appointmentId, String apartmentName, String appointmentTime) {
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

    public List<AssistantTaskState.RoomCandidate> safeCandidateRooms(AssistantTaskState currentState) {
        return currentState == null || currentState.candidateRooms() == null
                ? List.of()
                : currentState.candidateRooms();
    }

    public List<AssistantToolExecutionVo> buildToolExecutions(Result<String> assistantResult) {
        if (assistantResult == null || assistantResult.toolExecutions() == null || assistantResult.toolExecutions().isEmpty()) {
            return List.of();
        }

        return assistantResult.toolExecutions().stream()
                .map(this::toToolExecutionVo)
                .toList();
    }

    public List<AssistantKnowledgeSourceVo> buildKnowledgeSources(Result<String> assistantResult) {
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

    public String resolveAnswerSource(List<AssistantToolExecutionVo> toolExecutions,
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

    public String resolveFinishReason(FinishReason finishReason) {
        if (finishReason == null) {
            return "unknown";
        }
        return finishReason.name().toLowerCase(Locale.ROOT);
    }

    public String formatReply(String reply) {
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

    public List<String> splitParagraphs(String reply) {
        return Arrays.stream(reply.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
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

    private AssistantTaskState buildTaskStateFromToolExecution(ToolExecution toolExecution,
                                                               AssistantTaskState previousState) {
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

    private record AppointmentSelection(Long appointmentId, String label) {
    }
}
