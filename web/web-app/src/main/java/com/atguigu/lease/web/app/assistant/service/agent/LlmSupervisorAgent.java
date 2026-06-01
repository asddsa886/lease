package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LlmSupervisorAgent implements SupervisorAgent {

    private final ChatClient planningChatClient;
    private final AssistantPromptService promptService;
    private final AssistantSkillTemplateService skillTemplateService;
    private final SupervisorPlanValidator planValidator;
    private final Map<SpecialistAgentType, SpecialistAgent> specialistAgents;
    private final ObjectMapper objectMapper;

    public LlmSupervisorAgent(ChatClient planningChatClient,
                              AssistantPromptService promptService,
                              AssistantSkillTemplateService skillTemplateService,
                              SupervisorPlanValidator planValidator,
                              List<SpecialistAgent> specialistAgents,
                              ObjectMapper objectMapper) {
        this.planningChatClient = planningChatClient;
        this.promptService = promptService;
        this.skillTemplateService = skillTemplateService;
        this.planValidator = planValidator;
        this.objectMapper = objectMapper;
        this.specialistAgents = new LinkedHashMap<>();
        for (SpecialistAgent specialistAgent : specialistAgents) {
            this.specialistAgents.put(specialistAgent.type(), specialistAgent);
        }
    }

    @Override
    public SupervisorExecutionResult execute(SupervisorAgentRequest request) {
        SupervisorPlan plan = buildPlan(request);
        SupervisorPlanValidator.ValidatedSupervisorPlan validatedPlan =
                planValidator.validate(plan, request.userMessage());

        if (validatedPlan.clarification() != null) {
            return new SupervisorExecutionResult(
                    plan,
                    List.of(),
                    validatedPlan.primaryAgentType(),
                    validatedPlan.clarification().question(),
                    defaultClarificationActions(),
                    validatedPlan.clarification()
            );
        }

        List<SupervisorPlanStep> executedSteps = new ArrayList<>();
        List<SpecialistAgentResult> stepResults = new ArrayList<>();
        String sharedContext = "";

        for (SupervisorPlanStep step : validatedPlan.steps()) {
            SpecialistAgent specialistAgent = requireAgent(step.agentType());
            request.toolEventEmitter().emit("tool_call", step.agentType().getPlanName(),
                    "正在执行 " + step.agentType().getPlanName() + " 子任务");
            SpecialistAgentResult result = specialistAgent.execute(new SpecialistAgentRequest(
                    request.currentUser(),
                    request.conversationId(),
                    request.userMessage(),
                    request.history(),
                    request.longTermMemoryPrompt(),
                    step.objective(),
                    sharedContext,
                    request.toolEventEmitter()
            ));
            request.toolEventEmitter().emit("tool_result", step.agentType().getPlanName(),
                    "已完成 " + step.agentType().getPlanName() + " 子任务");
            executedSteps.add(step);
            stepResults.add(result);
            sharedContext = appendSharedContext(sharedContext, step.agentType(), result.reply());
        }

        SpecialistAgentType primaryType = validatedPlan.primaryAgentType();
        String finalReply = buildFinalReply(request, plan, stepResults, executedSteps);
        List<AssistantNextAction> nextActions = stepResults.isEmpty()
                ? promptService.defaultNextActions()
                : stepResults.get(stepResults.size() - 1).nextActions();

        return new SupervisorExecutionResult(
                plan,
                executedSteps,
                primaryType,
                finalReply,
                nextActions,
                null
        );
    }

    private SupervisorPlan buildPlan(SupervisorAgentRequest request) {
        String supervisorSkill = skillTemplateService.loadSkill("supervisor-routing");
        String systemPrompt = """
                %s

                当前角色：SupervisorAgent。
                你只负责做结构化执行计划，不直接回答业务细节。

                当前 Supervisor 技能手册：
                %s

                你必须输出严格 JSON，不要输出 Markdown，不要输出解释。
                JSON 字段固定为：
                {
                  "primaryAgent": "housing-advisor|order-service|customer-support",
                  "additionalAgents": ["housing-advisor|order-service|customer-support"],
                  "goal": "字符串",
                  "reason": "字符串",
                  "needsClarification": true/false,
                  "clarificationQuestion": "字符串"
                }
                约束：
                - 最多 3 个 agent，总数含 primaryAgent
                - additionalAgents 不能重复 primaryAgent
                - 如果问题是明显订单动作或订单状态问题，必须包含 order-service
                - 如果信息已经足够，不要提澄清问题
                - 只给最短执行链
                """.formatted(
                promptService.buildBaseSystemPrompt(request.currentUser()),
                supervisorSkill == null ? "" : supervisorSkill.trim()
        );

        String userPrompt = """
                用户问题：
                %s

                当前长期偏好记忆：
                %s
                """.formatted(
                request.userMessage(),
                request.longTermMemoryPrompt() == null || request.longTermMemoryPrompt().isBlank()
                        ? "无"
                        : request.longTermMemoryPrompt().trim()
        );

        request.toolEventEmitter().emit("tool_call", "supervisor-routing", "Supervisor 正在规划多 Agent 执行链路");
        String raw = planningChatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(userPrompt))
                .call()
                .content();
        request.toolEventEmitter().emit("tool_result", "supervisor-routing", "Supervisor 已生成执行计划");
        try {
            return objectMapper.readValue(raw, SupervisorPlan.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse supervisor plan", e);
        }
    }

    private SpecialistAgent requireAgent(SpecialistAgentType agentType) {
        SpecialistAgent specialistAgent = specialistAgents.get(agentType);
        if (specialistAgent == null) {
            throw new IllegalStateException("Missing specialist agent for type " + agentType);
        }
        return specialistAgent;
    }

    private String appendSharedContext(String existingContext, SpecialistAgentType type, String reply) {
        String fragment = """
                [%s]
                %s
                """.formatted(type.getPlanName(), reply == null ? "" : reply.trim());
        if (existingContext == null || existingContext.isBlank()) {
            return fragment.trim();
        }
        return existingContext.trim() + "\n\n" + fragment.trim();
    }

    private String buildFinalReply(SupervisorAgentRequest request,
                                   SupervisorPlan plan,
                                   List<SpecialistAgentResult> stepResults,
                                   List<SupervisorPlanStep> executedSteps) {
        if (stepResults.size() == 1) {
            return stepResults.get(0).reply();
        }

        StringBuilder workerContext = new StringBuilder();
        for (int i = 0; i < stepResults.size(); i++) {
            SupervisorPlanStep step = executedSteps.get(i);
            SpecialistAgentResult result = stepResults.get(i);
            workerContext.append("Agent: ").append(step.agentType().getPlanName()).append("\n")
                    .append(result.reply() == null ? "" : result.reply().trim())
                    .append("\n\n");
        }

        String systemPrompt = """
                %s

                当前角色：SupervisorAgent 汇总器。
                你需要基于多个 SpecialistAgent 的中间结果，给用户生成一版统一、简洁、无重复的最终答复。
                要求：
                - 不要重复逐个复述 agent 名称
                - 如果多个 agent 的结论可以合并，就自然合并
                - 优先回答用户原问题，再给下一步建议
                - 只使用已提供的中间结果，不要编造
                """.formatted(promptService.buildBaseSystemPrompt(request.currentUser()));

        String userPrompt = """
                用户原问题：
                %s

                本轮执行目标：
                %s

                以下是各 SpecialistAgent 的中间结果：
                %s
                """.formatted(
                request.userMessage(),
                plan.getGoal() == null ? "" : plan.getGoal(),
                workerContext.toString().trim()
        );

        return planningChatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(userPrompt))
                .call()
                .content();
    }

    private List<AssistantNextAction> defaultClarificationActions() {
        return List.of(
                new AssistantNextAction("补充预算", "我的预算是 3000 元"),
                new AssistantNextAction("查我的订单", "帮我查我的订单"),
                new AssistantNextAction("看签约流程", "看完房之后下一步怎么签约")
        );
    }
}
