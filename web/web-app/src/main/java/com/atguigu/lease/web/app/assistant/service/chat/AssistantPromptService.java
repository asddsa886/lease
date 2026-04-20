package com.atguigu.lease.web.app.assistant.service.chat;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.dto.AssistantTaskState;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class AssistantPromptService {

    public List<Message> buildPromptMessages(LoginUser currentUser,
                                             List<AssistantConversationMessage> history,
                                             String userMessage) {
        return buildPromptMessages(currentUser, history, userMessage, null);
    }

    public List<Message> buildPromptMessages(LoginUser currentUser,
                                             List<AssistantConversationMessage> history,
                                             String userMessage,
                                             String extraInstructions) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(currentUser, extraInstructions)));
        for (AssistantConversationMessage item : history) {
            if ("assistant".equalsIgnoreCase(item.getRole())) {
                messages.add(new AssistantMessage(item.getContent()));
            } else {
                messages.add(new UserMessage(item.getContent()));
            }
        }
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    public AssistantChatResponse buildResponse(String conversationId, String reply) {
        return buildResponse(
                conversationId,
                reply,
                new AssistantTaskState("chat", "completed"),
                defaultNextActions()
        );
    }

    public AssistantChatResponse buildResponse(String conversationId,
                                               String reply,
                                               AssistantTaskState taskState,
                                               List<AssistantNextAction> nextActions) {
        String normalizedReply = normalizeReply(reply);
        return AssistantChatResponse.builder()
                .conversationId(conversationId)
                .reply(normalizedReply)
                .paragraphs(splitParagraphs(normalizedReply))
                .taskState(taskState)
                .nextActions(nextActions == null || nextActions.isEmpty() ? defaultNextActions() : nextActions)
                .build();
    }

    public List<AssistantNextAction> defaultNextActions() {
        return List.of(
                new AssistantNextAction("查房源", "帮我查北京 3000 以内的房源"),
                new AssistantNextAction("查预约", "帮我看看我的预约"),
                new AssistantNextAction("查订单", "帮我看看我的签约订单")
        );
    }

    public String buildBaseSystemPrompt(LoginUser currentUser) {
        return """
                你是租房平台的 AI 助手，只能使用中文回答。
                当前时间：%s
                当前用户ID：%s
                当前用户名：%s

                你的通用职责：
                1. 帮用户理解公寓、房间、预约和签约订单相关信息。
                2. 只要涉及平台数据查询或业务动作，就优先调用工具。
                3. 只有在信息缺失会显著影响结果时，才追问用户。

                你的硬性规则：
                - 不要编造公寓、房间、预约或订单数据。
                - 不要编造价格、ID、状态或日期。
                - 工具失败时，要直接说明原因，并给出下一步建议。
                - 回复保持简洁、清楚、实用。
                - 如果能用中文名称确认信息，就不要优先向用户索要内部数字ID。
                - 创建、取消、改期这类动作执行成功后，必须明确复述最终结果。
                - 预约时间和起租日期都按中国时区本地语义理解。
                - 如果用户说“明天下午12点”或“周六下午3点”，必须保持这个本地时间语义，绝不能转换成 UTC。
                """.formatted(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                currentUser.getId(),
                currentUser.getUsername()
        );
    }

    private String buildSystemPrompt(LoginUser currentUser, String extraInstructions) {
        String basePrompt = buildBaseSystemPrompt(currentUser);
        if (extraInstructions == null || extraInstructions.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n\n当前专员补充规则：\n" + extraInstructions.trim();
    }

    private String normalizeReply(String reply) {
        if (reply == null || reply.isBlank()) {
            return "我这次没有拿到有效回复，你可以换一种问法再试一次。";
        }
        return reply.trim();
    }

    private List<String> splitParagraphs(String reply) {
        return Arrays.stream(reply.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .toList();
    }
}
