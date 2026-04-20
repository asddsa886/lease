package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

public class AssistantSupervisorAgent {

    private static final String SUPERVISOR_PROMPT = """
            你是租房助手的路由协调器，负责判断当前请求应该交给哪个专员处理。

            可选路由：
            - general：问候、能力说明、模糊问题、暂不支持的需求
            - room_search：找房、筛选、比较、推荐公寓或房间
            - appointment：预约列表、创建预约、取消预约、修改预约
            - lease_order：订单列表、订单详情、创建订单、取消订单
            - rental_workflow：跨阶段复合任务，例如先找房，再预约，或者先推荐，再下单

            你只能输出 JSON，格式必须严格如下：
            {"route":"general|room_search|appointment|lease_order|rental_workflow","reason":"...","goal":"..."}

            如果用户是在做链路式任务，例如“先推荐三套，再帮我预约”或“先找房，再帮我下单”，优先选择 rental_workflow。
            route 字段必须使用英文枚举值，其他解释用中文。
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AssistantRoutingPolicy routingPolicy;

    public AssistantSupervisorAgent(ChatModel chatModel,
                                    ObjectMapper objectMapper,
                                    AssistantRoutingPolicy routingPolicy) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.routingPolicy = routingPolicy;
    }

    public AssistantSupervisorDecision decide(LoginUser currentUser,
                                              List<AssistantConversationMessage> history,
                                              String userMessage) {
        AssistantAgentRoute fallbackRoute = routingPolicy.classify(userMessage);
        try {
            String content = chatClient.prompt()
                    .messages(buildSupervisorMessages(currentUser, history, userMessage))
                    .call()
                    .content();
            AssistantSupervisorDecision parsedDecision = parseDecision(content);
            if (parsedDecision.route() == null) {
                return AssistantSupervisorDecision.fallback(fallbackRoute, "路由结果为空，已使用规则兜底");
            }
            if (parsedDecision.route() == AssistantAgentRoute.GENERAL && fallbackRoute != AssistantAgentRoute.GENERAL) {
                return AssistantSupervisorDecision.fallback(fallbackRoute, "大模型路由过于宽泛，已使用规则兜底");
            }
            return parsedDecision;
        } catch (Exception ignored) {
            return AssistantSupervisorDecision.fallback(fallbackRoute, "路由判定异常，已使用规则兜底");
        }
    }

    private List<Message> buildSupervisorMessages(LoginUser currentUser,
                                                  List<AssistantConversationMessage> history,
                                                  String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SUPERVISOR_PROMPT));
        messages.add(new UserMessage("""
                当前用户ID：%s
                当前用户名：%s

                最近对话：
                %s

                用户最新请求：
                %s
                """.formatted(
                currentUser.getId(),
                currentUser.getUsername(),
                renderRecentHistory(history),
                userMessage
        )));
        return messages;
    }

    private AssistantSupervisorDecision parseDecision(String content) throws Exception {
        String normalized = stripCodeFence(content);
        JsonNode root = objectMapper.readTree(normalized);
        AssistantAgentRoute route = AssistantAgentRoute.fromValue(root.path("route").asText());
        return new AssistantSupervisorDecision(
                route,
                root.path("reason").asText(""),
                root.path("goal").asText("")
        );
    }

    private String renderRecentHistory(List<AssistantConversationMessage> history) {
        if (history == null || history.isEmpty()) {
            return "（无）";
        }
        int fromIndex = Math.max(history.size() - 6, 0);
        StringBuilder builder = new StringBuilder();
        for (AssistantConversationMessage item : history.subList(fromIndex, history.size())) {
            builder.append(item.getRole()).append(": ").append(item.getContent()).append('\n');
        }
        return builder.toString().trim();
    }

    private String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineBreak >= 0 && lastFence > firstLineBreak) {
                return trimmed.substring(firstLineBreak + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
