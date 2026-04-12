package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskState;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskStateStore;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.memory.AssistantMongoChatMemoryStore;
import com.atguigu.lease.web.app.chat.tool.RentalAssistantTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantChatServiceTest {

    @Mock
    private ObjectProvider<RentalAssistant> rentalAssistantProvider;
    @Mock
    private ObjectProvider<StreamingRentalAssistant> streamingRentalAssistantProvider;
    @Mock
    private ObjectProvider<AppointmentActionAnalyzer> appointmentActionAnalyzerProvider;
    @Mock
    private ObjectProvider<AssistantMongoChatMemoryStore> assistantChatMemoryStoreProvider;
    @Mock
    private RentalAssistantTools rentalAssistantTools;
    @Mock
    private AssistantWorkflowOrchestrator assistantWorkflowOrchestrator;

    private AssistantTaskStateStore assistantTaskStateStore;
    private AssistantChatService assistantChatService;

    @BeforeEach
    void setUp() {
        AssistantProperties assistantProperties = new AssistantProperties();
        assistantProperties.setEnabled(true);
        assistantTaskStateStore = new AssistantTaskStateStore();
        ObjectMapper objectMapper = new ObjectMapper();
        AssistantConversationSupport conversationSupport = new AssistantConversationSupport(assistantTaskStateStore, objectMapper);
        AssistantDeterministicToolExecutor deterministicToolExecutor = new AssistantDeterministicToolExecutor(
                rentalAssistantTools,
                assistantTaskStateStore,
                conversationSupport,
                objectMapper
        );
        assistantChatService = new AssistantChatService(
                assistantProperties,
                rentalAssistantProvider,
                streamingRentalAssistantProvider,
                appointmentActionAnalyzerProvider,
                assistantChatMemoryStoreProvider,
                rentalAssistantTools,
                assistantTaskStateStore,
                assistantWorkflowOrchestrator,
                deterministicToolExecutor,
                conversationSupport
        );
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    void chat_shouldUseLocalAppointmentStateMachineBeforeInvokingModel() {
        LoginUserHolder.set(new LoginUser(8L, "17503976585"));
        assistantTaskStateStore.save(
                "room-chat-001",
                new AssistantTaskState("ROOM_DETAIL", "WAITING_USER_INPUT", 2L, "Huilongguan Room 102", 12L, null, List.of())
        );

        AssistantChatResponseVo response = assistantChatService.chat("帮我预约明天下午", "room-chat-001");

        assertEquals("APPOINTMENT_CONFIRMING", response.getTaskState().getTaskType());
        assertEquals("WAITING_CONFIRMATION", response.getTaskState().getTaskStatus());
        assertEquals(2L, response.getTaskState().getSelectedRoomId());
        assertFalse(response.getTaskState().getProposedAppointmentTime().isBlank());
        verify(assistantWorkflowOrchestrator, never()).orchestrate(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void chat_shouldUseLastToolExecutionToResolveTaskState() {
        ToolExecution queryAppointments = mock(ToolExecution.class, RETURNS_DEEP_STUBS);
        when(queryAppointments.request().name()).thenReturn("getMyAppointments");
        when(queryAppointments.request().arguments()).thenReturn("{}");
        when(queryAppointments.result()).thenReturn("""
                {
                  "tool":"getMyAppointments",
                  "items":[
                    {"appointmentId":13,"apartmentName":"Wendu Apartment","appointmentTime":"2026-04-11 15:00","appointmentStatusCode":1}
                  ]
                }
                """);

        ToolExecution cancelAppointment = mock(ToolExecution.class, RETURNS_DEEP_STUBS);
        when(cancelAppointment.request().name()).thenReturn("cancelAppointment");
        when(cancelAppointment.request().arguments()).thenReturn("{\"appointmentId\":13}");
        when(cancelAppointment.result()).thenReturn("""
                {
                  "tool":"cancelAppointment",
                  "summary":"已为你取消这条看房预约。",
                  "appointmentId":13,
                  "appointmentStatusCode":2,
                  "appointmentStatusText":"已取消",
                  "apartmentName":"Wendu Apartment",
                  "appointmentTime":"2026-04-11 15:00"
                }
                """);

        RentalAssistant rentalAssistant = mock(RentalAssistant.class);
        when(rentalAssistant.chat(anyString(), anyString()))
                .thenReturn(new Result<>("已为你取消这条看房预约。", null, List.of(), null, List.of(queryAppointments, cancelAppointment)));
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(rentalAssistant);
        when(assistantWorkflowOrchestrator.orchestrate(anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(invocation -> new AssistantWorkflowOrchestrator.OrchestrationResult(
                        invocation.getArgument(0),
                        invocation.getArgument(0),
                        false,
                        "none",
                        "",
                        ""
                ));

        AssistantChatResponseVo response = assistantChatService.chat("帮我取消最新预约", "room-chat-002");

        assertEquals("APPOINTMENT_CANCELED", response.getTaskState().getTaskType());
        assertEquals("COMPLETED", response.getTaskState().getTaskStatus());
        assertEquals("VIEW_APPOINTMENTS", response.getNextActions().get(0).getAction());
    }

    @Test
    void chat_shouldExecuteDeterministicAppointmentQueryWhenOrchestratedToolIsKnown() {
        LoginUserHolder.set(new LoginUser(8L, "17503976585"));
        when(assistantWorkflowOrchestrator.orchestrate(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new AssistantWorkflowOrchestrator.OrchestrationResult(
                        "查看您的所有预约记录",
                        "查询用户的所有预约记录",
                        true,
                        "appointment_query",
                        "getMyAppointments",
                        """
                                {"businessQuery":true,"requiresTool":true,"intent":"appointment_query","suggestedTool":"getMyAppointments","rewrittenUserMessage":"查询用户的所有预约记录"}
                                """
                ));
        when(rentalAssistantTools.getMyAppointments()).thenReturn("""
                {
                  "tool":"getMyAppointments",
                  "summary":"当前共有 1 条预约记录。",
                  "items":[
                    {"appointmentId":13,"apartmentName":"Wendu Apartment","appointmentTime":"2026-04-11 15:00","appointmentStatusCode":1,"statusText":"待看房"}
                  ]
                }
                """);

        AssistantChatResponseVo response = assistantChatService.chat("查看您的所有预约记录", "room-chat-003");

        assertEquals("tool", response.getAnswerSource());
        assertEquals("APPOINTMENT_QUERY", response.getTaskState().getTaskType());
        assertEquals("CANCEL_LATEST_APPOINTMENT", response.getNextActions().get(0).getAction());
        assertEquals("getMyAppointments", response.getToolExecutions().get(0).getToolName());
        verify(rentalAssistantProvider, never()).getIfAvailable();
    }

    @Test
    void chat_shouldExecuteDeterministicRoomSearchWhenOrchestratedToolIsKnown() {
        when(assistantWorkflowOrchestrator.orchestrate(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new AssistantWorkflowOrchestrator.OrchestrationResult(
                        "帮我查一下北京市 3000 以内的房源",
                        "帮我查询北京市月租3000元以内的房源",
                        true,
                        "room_search",
                        "searchRooms",
                        """
                                {"businessQuery":true,"requiresTool":true,"intent":"room_search","suggestedTool":"searchRooms","rewrittenUserMessage":"帮我查询北京市月租3000元以内的房源"}
                                """
                ));
        when(rentalAssistantTools.searchRooms(eq("北京市"), isNull(), eq(new BigDecimal("3000")))).thenReturn("""
                {
                  "tool":"searchRooms",
                  "summary":"共找到 1 套房源，当前返回 1 套。",
                  "items":[
                    {"roomId":2,"title":"Wendu Room 101","locationText":"北京市 昌平区","rentText":"3000元/月","labels":["近地铁"]}
                  ]
                }
                """);

        AssistantChatResponseVo response = assistantChatService.chat("帮我查一下北京市 3000 以内的房源", "room-chat-004");

        assertEquals("tool", response.getAnswerSource());
        assertEquals("ROOM_SEARCH", response.getTaskState().getTaskType());
        assertEquals("VIEW_ROOM_DETAIL", response.getNextActions().get(0).getAction());
        assertEquals("searchRooms", response.getToolExecutions().get(0).getToolName());
        verify(rentalAssistantProvider, never()).getIfAvailable();
    }

    @Test
    void chat_shouldEnterRescheduleConfirmationWhenQuestionContainsDayOfMonthTime() {
        LoginUserHolder.set(new LoginUser(8L, "17503976585"));
        assistantTaskStateStore.save(
                "room-chat-005",
                new AssistantTaskState(
                        "APPOINTMENT_QUERY",
                        "COMPLETED",
                        null,
                        null,
                        null,
                        14L,
                        "预约ID 14 / 温都水城社区 / 2026-04-11 12:00",
                        null,
                        List.of()
                )
        );

        AssistantChatResponseVo response = assistantChatService.chat("把这条预约改到11号的11点", "room-chat-005");

        assertEquals("APPOINTMENT_RESCHEDULE_CONFIRMING", response.getTaskState().getTaskType());
        assertEquals("WAITING_CONFIRMATION", response.getTaskState().getTaskStatus());
        assertEquals(14L, response.getTaskState().getSelectedAppointmentId());
        assertEquals("CONFIRM_RESCHEDULE_APPOINTMENT", response.getNextActions().get(0).getAction());
        assertEquals("agent", response.getAnswerSource());
        assertEquals("11:00", response.getTaskState().getProposedAppointmentTime().substring(11));
        verify(assistantWorkflowOrchestrator, never()).orchestrate(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void chat_shouldResolveRoomDetailFromRoomSearchCandidates() {
        assistantTaskStateStore.save(
                "room-chat-006",
                new AssistantTaskState(
                        "ROOM_SEARCH",
                        "WAITING_USER_INPUT",
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                new AssistantTaskState.RoomCandidate(2L, "温都水城社区 101"),
                                new AssistantTaskState.RoomCandidate(3L, "回龙观社区 102")
                        )
                )
        );
        when(assistantWorkflowOrchestrator.orchestrate(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new AssistantWorkflowOrchestrator.OrchestrationResult(
                        "温都水城社区 101 介绍一下",
                        "查询温都水城社区 101 的房间详情",
                        true,
                        "room_detail",
                        "getRoomDetail",
                        """
                                {"businessQuery":true,"requiresTool":true,"intent":"room_detail","suggestedTool":"getRoomDetail","rewrittenUserMessage":"查询温都水城社区 101 的房间详情"}
                                """
                ));
        when(rentalAssistantTools.getRoomDetail(2L)).thenReturn("""
                {
                  "tool":"getRoomDetail",
                  "summary":"已获取房间详情。",
                  "roomId":2,
                  "apartmentId":12,
                  "title":"温都水城社区 101",
                  "locationText":"北京市 昌平区",
                  "rentText":"2500元/月",
                  "labels":["朝南","独卫"]
                }
                """);

        AssistantChatResponseVo response = assistantChatService.chat("温都水城社区 101 介绍一下", "room-chat-006");

        assertEquals("tool", response.getAnswerSource());
        assertEquals("ROOM_DETAIL", response.getTaskState().getTaskType());
        assertEquals(2L, response.getTaskState().getSelectedRoomId());
        assertEquals("ASK_APPOINTMENT", response.getNextActions().get(0).getAction());
        assertEquals("getRoomDetail", response.getToolExecutions().get(0).getToolName());
        verify(rentalAssistantProvider, never()).getIfAvailable();
    }
}
