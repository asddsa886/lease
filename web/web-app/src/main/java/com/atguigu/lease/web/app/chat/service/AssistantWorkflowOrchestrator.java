package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.web.app.chat.agent.AssistantTaskState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.ZonedDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantWorkflowOrchestrator {

    private final ObjectProvider<BusinessIntentAnalyzer> businessIntentAnalyzerProvider;
    private final ObjectMapper objectMapper;

    public OrchestrationResult orchestrate(String question,
                                           String conversationId,
                                           AssistantTaskState currentState,
                                           boolean loggedIn) {
        if (!StringUtils.hasText(question)) {
            return new OrchestrationResult(question, question, false, "none", "", "");
        }

        BusinessIntentAnalyzer analyzer = businessIntentAnalyzerProvider.getIfAvailable();
        if (analyzer == null) {
            return new OrchestrationResult(question, question, false, "none", "", "");
        }

        try {
            String raw = analyzer.analyze(buildAnalyzerInput(question, currentState, loggedIn));
            WorkflowIntentDecision decision = parseDecision(raw);
            if (decision == null || !decision.requiresTool()) {
                return new OrchestrationResult(question, question, false, "none", "", raw);
            }

            String orchestratedQuestion = buildExecutionPrompt(question, currentState, loggedIn, decision);
            log.info(
                    "Assistant workflow orchestrated, conversationId={}, intent={}, suggestedTool={}, rewrittenUserMessage={}, question={}",
                    conversationId,
                    decision.intent(),
                    decision.suggestedTool(),
                    decision.rewrittenUserMessage(),
                    question
            );
            return new OrchestrationResult(
                    question,
                    orchestratedQuestion,
                    true,
                    blankToEmpty(decision.intent()),
                    blankToEmpty(decision.suggestedTool()),
                    raw
            );
        } catch (Exception e) {
            log.warn("Assistant workflow orchestration skipped, conversationId={}", conversationId, e);
            return new OrchestrationResult(question, question, false, "none", "", "");
        }
    }

    private String buildAnalyzerInput(String question, AssistantTaskState currentState, boolean loggedIn) {
        return """
                now=%s
                loggedIn=%s
                currentTaskType=%s
                currentTaskStatus=%s
                selectedRoomId=%s
                selectedRoomTitle=%s
                selectedApartmentId=%s
                selectedAppointmentId=%s
                selectedAppointmentLabel=%s
                proposedAppointmentTime=%s
                candidateRooms=%s
                userQuestion=%s
                """.formatted(
                ZonedDateTime.now(),
                loggedIn,
                currentState == null ? "" : blankToEmpty(currentState.taskType()),
                currentState == null ? "" : blankToEmpty(currentState.taskStatus()),
                currentState == null || currentState.selectedRoomId() == null ? "" : currentState.selectedRoomId(),
                currentState == null ? "" : blankToEmpty(currentState.selectedRoomTitle()),
                currentState == null || currentState.selectedApartmentId() == null ? "" : currentState.selectedApartmentId(),
                currentState == null || currentState.selectedAppointmentId() == null ? "" : currentState.selectedAppointmentId(),
                currentState == null ? "" : blankToEmpty(currentState.selectedAppointmentLabel()),
                currentState == null ? "" : blankToEmpty(currentState.proposedAppointmentTime()),
                currentState == null || currentState.candidateRooms() == null ? "[]" : currentState.candidateRooms(),
                question
        );
    }

    private WorkflowIntentDecision parseDecision(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            return new WorkflowIntentDecision(
                    root.path("businessQuery").asBoolean(false),
                    root.path("requiresTool").asBoolean(false),
                    getText(root, "intent"),
                    getText(root, "suggestedTool"),
                    getText(root, "rewrittenUserMessage"),
                    getText(root, "reason")
            );
        } catch (Exception e) {
            log.warn("Assistant workflow decision parse failed, raw={}", raw, e);
            return null;
        }
    }

    private String buildExecutionPrompt(String originalQuestion,
                                        AssistantTaskState currentState,
                                        boolean loggedIn,
                                        WorkflowIntentDecision decision) {
        return """
                这是智慧公寓租赁平台中的一轮真实业务对话，请严格按平台业务处理。

                强制执行规则：
                1. 这是一条平台业务请求，你必须优先调用平台工具，不允许先回答公开市场信息。
                2. 如果 suggestedTool 不为空，你必须优先尝试调用 suggestedTool。
                3. 对房源、房间详情、预约、租约、价格、时间、状态这类问题，禁止使用“根据公开资料”或“市场上通常”这类泛化回答。
                4. 如果当前会话状态已经能确定房源、预约或租约对象，要结合上下文直接处理，不要无意义追问。
                5. 如果工具返回空结果，就如实说明平台当前没查到，不要编造。
                6. 只有工具明确无法处理且问题明显属于常识说明时，才允许不用工具。

                当前登录状态：
                - loggedIn: %s

                当前会话状态：
                - taskType: %s
                - taskStatus: %s
                - selectedRoomId: %s
                - selectedRoomTitle: %s
                - selectedApartmentId: %s
                - selectedAppointmentId: %s
                - selectedAppointmentLabel: %s
                - proposedAppointmentTime: %s
                - candidateRooms: %s

                本轮工作流编排结果：
                - intent: %s
                - suggestedTool: %s
                - rewrittenUserMessage: %s
                - reason: %s

                请优先按下面这句去理解并执行用户需求：
                %s

                用户原始输入：
                %s

                现在开始处理这轮对话。记住：如果这是业务数据问题，你必须先调工具。
                """.formatted(
                loggedIn,
                currentState == null ? "" : blankToEmpty(currentState.taskType()),
                currentState == null ? "" : blankToEmpty(currentState.taskStatus()),
                currentState == null || currentState.selectedRoomId() == null ? "" : currentState.selectedRoomId(),
                currentState == null ? "" : blankToEmpty(currentState.selectedRoomTitle()),
                currentState == null || currentState.selectedApartmentId() == null ? "" : currentState.selectedApartmentId(),
                currentState == null || currentState.selectedAppointmentId() == null ? "" : currentState.selectedAppointmentId(),
                currentState == null ? "" : blankToEmpty(currentState.selectedAppointmentLabel()),
                currentState == null ? "" : blankToEmpty(currentState.proposedAppointmentTime()),
                currentState == null || currentState.candidateRooms() == null ? "[]" : currentState.candidateRooms(),
                blankToEmpty(decision.intent()),
                blankToEmpty(decision.suggestedTool()),
                blankToEmpty(decision.rewrittenUserMessage()),
                blankToEmpty(decision.reason()),
                blankToEmpty(decision.rewrittenUserMessage()),
                originalQuestion
        );
    }

    private String getText(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? "" : child.asText("");
    }

    private String blankToEmpty(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    public record OrchestrationResult(String originalQuestion,
                                      String question,
                                      boolean orchestrated,
                                      String intent,
                                      String suggestedTool,
                                      String rawDecision) {
    }

    private record WorkflowIntentDecision(boolean businessQuery,
                                          boolean requiresTool,
                                          String intent,
                                          String suggestedTool,
                                          String rewrittenUserMessage,
                                          String reason) {
    }
}
