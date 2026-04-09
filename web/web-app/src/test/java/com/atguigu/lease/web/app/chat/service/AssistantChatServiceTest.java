package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskState;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskStateStore;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.memory.AssistantMongoChatMemoryStore;
import com.atguigu.lease.web.app.chat.tool.RentalAssistantTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
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
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantChatServiceTest {

    @Mock
    private ObjectProvider<RentalAssistant> rentalAssistantProvider;
    @Mock
    private ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;
    @Mock
    private ObjectProvider<AssistantMongoChatMemoryStore> assistantChatMemoryStoreProvider;
    @Mock
    private RentalAssistantTools rentalAssistantTools;

    private AssistantProperties assistantProperties;
    private AssistantChatService assistantChatService;
    private AssistantTaskStateStore assistantTaskStateStore;

    @BeforeEach
    void setUp() {
        assistantProperties = new AssistantProperties();
        assistantProperties.setEnabled(true);
        assistantTaskStateStore = new AssistantTaskStateStore();
        assistantChatService = new AssistantChatService(
                assistantProperties,
                rentalAssistantProvider,
                streamingRentalAssistantProvider,
                assistantChatMemoryStoreProvider,
                rentalAssistantTools,
                assistantTaskStateStore,
                new ObjectMapper()
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
        assertEquals(List.of(), response.getNextActions());
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

    @Test
    void chat_shouldBuildTaskStateAndNextActionsForRoomSearch() {
        ToolExecution toolExecution = mock(ToolExecution.class, RETURNS_DEEP_STUBS);
        when(toolExecution.request().name()).thenReturn("searchRooms");
        when(toolExecution.request().arguments()).thenReturn("{\"districtName\":\"Beijing\",\"maxRent\":3000}");
        when(toolExecution.result()).thenReturn("""
                {"tool":"searchRooms","summary":"2 rooms found.","items":[
                  {"roomId":2,"title":"Wendu Room 101"},
                  {"roomId":3,"title":"Huilongguan Room 102"}
                ]}
                """);
        when(toolExecution.hasFailed()).thenReturn(false);
        when(rentalAssistantProvider.getIfAvailable()).thenReturn((conversationId, message) ->
                new Result<String>("Two rooms found.", null, List.of(), null, List.of(toolExecution)));

        AssistantChatResponseVo response = assistantChatService.chat("Find rooms in Beijing within 3000", "room-chat-001");

        assertEquals("ROOM_SEARCH", response.getTaskState().getTaskType());
        assertEquals("WAITING_USER_INPUT", response.getTaskState().getTaskStatus());
        assertEquals(2, response.getTaskState().getCandidateRooms().size());
        assertEquals(3, response.getNextActions().size());
        assertEquals("VIEW_ROOM_DETAIL", response.getNextActions().get(0).getAction());
    }

    @Test
    void chat_shouldEnterAppointmentConfirmingStateForRoomDetailConversation() {
        LoginUserHolder.set(new LoginUser(8L, "17503976585"));
        assistantTaskStateStore.save(
                "room-chat-001",
                new AssistantTaskState("ROOM_DETAIL", "WAITING_USER_INPUT", 2L, "Huilongguan Room 102", 12L, null, List.of())
        );
        when(rentalAssistantProvider.getIfAvailable()).thenReturn((conversationId, message) -> result("unused"));

        AssistantChatResponseVo response = assistantChatService.chat("帮我预约明天下午", "room-chat-001");

        assertEquals("APPOINTMENT_CONFIRMING", response.getTaskState().getTaskType());
        assertEquals("WAITING_CONFIRMATION", response.getTaskState().getTaskStatus());
        assertEquals(2L, response.getTaskState().getSelectedRoomId());
        assertEquals(12L, response.getTaskState().getSelectedApartmentId());
        assertFalse(response.getTaskState().getProposedAppointmentTime().isBlank());
        assertEquals("CONFIRM_APPOINTMENT", response.getNextActions().get(0).getAction());
    }

    @Test
    void chat_shouldCreateAppointmentAfterConfirmation() {
        LoginUserHolder.set(new LoginUser(8L, "17503976585"));
        assistantTaskStateStore.save(
                "room-chat-001",
                new AssistantTaskState("APPOINTMENT_CONFIRMING", "WAITING_CONFIRMATION", 2L, "Huilongguan Room 102", 12L, "明天下午", List.of())
        );
        when(rentalAssistantProvider.getIfAvailable()).thenReturn((conversationId, message) -> result("unused"));
        when(rentalAssistantTools.createRoomAppointment(2L, "明天下午", null)).thenReturn("""
                {
                  "tool": "createRoomAppointment",
                  "summary": "预约已创建成功",
                  "appointmentId": 1001,
                  "appointmentTime": "明天下午",
                  "roomId": 2,
                  "apartmentId": 12,
                  "title": "Huilongguan Room 102"
                }
                """);

        AssistantChatResponseVo response = assistantChatService.chat("确认", "room-chat-001");

        assertEquals("tool", response.getAnswerSource());
        assertEquals("APPOINTMENT_CREATED", response.getTaskState().getTaskType());
        assertEquals("COMPLETED", response.getTaskState().getTaskStatus());
        assertEquals("明天下午", response.getTaskState().getProposedAppointmentTime());
        assertEquals("VIEW_APPOINTMENTS", response.getNextActions().get(0).getAction());
    }

    private Result<String> result(String content) {
        return new Result<>(content, null, List.of(), null, List.of());
    }
}
