package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantChatServiceTest {

    @Mock
    private ObjectProvider<RentalAssistant> rentalAssistantProvider;
    @Mock
    private ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;

    private AssistantProperties assistantProperties;
    private AssistantChatService assistantChatService;

    @BeforeEach
    void setUp() {
        assistantProperties = new AssistantProperties();
        assistantProperties.setEnabled(true);
        assistantChatService = new AssistantChatService(
                assistantProperties,
                rentalAssistantProvider,
                streamingRentalAssistantProvider
        );
    }

    @Test
    void chat_shouldFormatReplyAndSplitParagraphs() {
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(message -> """
                第一段
                - 第二段
                """);

        AssistantChatResponseVo response = assistantChatService.chat(" 你好 ");

        assertEquals("第一段\n• 第二段", response.getReply());
        assertEquals(List.of("第一段\n• 第二段"), response.getParagraphs());
    }

    @Test
    void chat_shouldReturnFallbackWhenModelReplyIsBlank() {
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(message -> "   ");

        AssistantChatResponseVo response = assistantChatService.chat("你可以帮我做什么？");

        assertEquals("我当前没有生成有效回复，请换个问法再试。", response.getReply());
        assertEquals(List.of("我当前没有生成有效回复，请换个问法再试。"), response.getParagraphs());
    }

    @Test
    void chat_shouldRejectWhenAssistantDisabled() {
        assistantProperties.setEnabled(false);

        LeaseException exception = assertThrows(LeaseException.class, () -> assistantChatService.chat("你好"));

        assertEquals(ResultCodeEnum.SERVICE_ERROR.getCode(), exception.getCode());
    }
}
