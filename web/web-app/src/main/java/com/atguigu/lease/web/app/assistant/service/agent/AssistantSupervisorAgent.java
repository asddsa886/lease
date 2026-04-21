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
            - search_qa：问候、能力说明、房源检索、平台规则说明、知识问答、浏览记录查询
            - business_execution：预约查询/创建/取消/改约、签约订单查询/创建/取消、其他需要执行业务动作的请求

            你只能输出 JSON，格式必须严格如下：
            {"route":"search_qa|business_execution","reason":"...","goal":"..."}

            如果用户请求中包含“创建/取消/改约/下单/提交/支付”等执行动作，优先选择 business_execution。
            route 字段必须使用英文枚举值，reason 和 goal 用中文。
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
            if (parsedDecision.route() == AssistantAgentRoute.SEARCH_QA
                    && fallbackRoute == AssistantAgentRoute.BUSINESS_EXECUTION) {
                return AssistantSupervisorDecision.fallback(fallbackRoute, "检测到业务执行意图，已按规则切换执行专员");
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
