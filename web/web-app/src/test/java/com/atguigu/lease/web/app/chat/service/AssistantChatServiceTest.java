package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.memory.AssistantMongoChatMemoryStore;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantChatServiceTest {

    @Mock
    private ObjectProvider<RentalAssistant> rentalAssistantProvider;
    @Mock
    private ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;
    @Mock
    private ObjectProvider<AssistantMongoChatMemoryStore> assistantChatMemoryStoreProvider;

    private AssistantProperties assistantProperties;
    private AssistantChatService assistantChatService;

    @BeforeEach
    void setUp() {
        assistantProperties = new AssistantProperties();
        assistantProperties.setEnabled(true);
        assistantChatService = new AssistantChatService(
                assistantProperties,
                rentalAssistantProvider,
                streamingRentalAssistantProvider,
                assistantChatMemoryStoreProvider
        );
    }

    @Test
    void chat_shouldFormatReplyAndSplitParagraphs() {
        when(rentalAssistantProvider.getIfAvailable()).thenReturn((conversationId, message) ->
                result("""
                        第一段
                        - 第二段
                        """));

        AssistantChatResponseVo response = assistantChatService.chat(" 你好 ");

        assertFalse(response.getConversationId().isBlank());
        assertEquals("第一段\n• 第二段", response.getReply());
        assertEquals(List.of("第一段\n• 第二段"), response.getParagraphs());
        assertEquals("model", response.getAnswerSource());
        assertEquals("unknown", response.getFinishReason());
        assertEquals(List.of(), response.getToolExecutions());
        assertEquals(List.of(), response.getKnowledgeSources());
    }

    @Test
    void chat_shouldReturnFallbackWhenModelReplyIsBlank() {
        when(rentalAssistantProvider.getIfAvailable()).thenReturn((conversationId, message) -> result("   "));

        AssistantChatResponseVo response = assistantChatService.chat("你可以帮我做什么？");

        assertEquals("我当前没有生成有效回复，请换个问法再试。", response.getReply());
        assertEquals(List.of("我当前没有生成有效回复，请换个问法再试。"), response.getParagraphs());
    }

    @Test
    void chat_shouldReuseConversationId() {
        when(rentalAssistantProvider.getIfAvailable()).thenReturn((conversationId, message) -> result("好的"));

        AssistantChatResponseVo response = assistantChatService.chat("你好", "room-chat-001");

        assertEquals("room-chat-001", response.getConversationId());
    }

    @Test
    void chat_shouldRejectWhenAssistantDisabled() {
        assistantProperties.setEnabled(false);

        LeaseException exception = assertThrows(LeaseException.class, () -> assistantChatService.chat("你好"));

        assertEquals(ResultCodeEnum.SERVICE_ERROR.getCode(), exception.getCode());
    }

    private Result<String> result(String content) {
        return new Result<>(content, null, List.of(), null, List.of());
    }
}
