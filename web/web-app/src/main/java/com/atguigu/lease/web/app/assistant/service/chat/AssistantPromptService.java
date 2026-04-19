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
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(currentUser)));
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
        String normalizedReply = normalizeReply(reply);
        return AssistantChatResponse.builder()
                .conversationId(conversationId)
                .reply(normalizedReply)
                .paragraphs(splitParagraphs(normalizedReply))
                .taskState(new AssistantTaskState("chat", "completed"))
                .nextActions(defaultNextActions())
                .build();
    }

    public List<AssistantNextAction> defaultNextActions() {
        return List.of(
                new AssistantNextAction("查房源", "帮我查北京 3000 以内的房源"),
                new AssistantNextAction("查预约", "帮我看看我的预约"),
                new AssistantNextAction("查订单", "帮我看看我的签约订单")
        );
    }

    private String buildSystemPrompt(LoginUser currentUser) {
        return """
                You are the AI rental assistant for the lease application.
                Always answer in Chinese.
                Current time: %s.
                Current user id: %s.
                Current username: %s.

                Your responsibilities:
                1. Help users understand apartments, rooms, appointments, and lease orders.
                2. Use tools for any platform data query or business action.
                3. Ask follow-up questions only when the missing information would materially change the result.

                Hard rules:
                - Do not invent apartment, room, appointment, or order data.
                - Do not invent prices, ids, status, or dates.
                - If a tool returns a failure, explain the reason directly and suggest the next step.
                - Keep the answer concise, structured, and practical.
                - For create/cancel/reschedule actions, clearly restate the final result after the tool call.
                - For room search, payment type is optional unless the user explicitly wants that filter.
                - The room search tool accepts province, city, district and payment type by Chinese names directly. Never ask the user for numeric ids.
                - If the user gives a city plus a rent budget, call the room search tool directly instead of asking for extra conditions.
                - If the user says "随便", you may omit payment type and use ascending rent order by default.
                """.formatted(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                currentUser.getId(),
                currentUser.getUsername()
        );
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
