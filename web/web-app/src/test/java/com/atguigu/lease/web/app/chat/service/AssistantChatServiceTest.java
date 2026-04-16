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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
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
    private ObjectProvider<AssistantMongoChatMemoryStore> assistantChatMemoryStoreProvider;
    @Mock
    private RentalAssistantTools rentalAssistantTools;

    private AssistantTaskStateStore assistantTaskStateStore;
    private AssistantChatService assistantChatService;

    @BeforeEach
    void setUp() {
        AssistantProperties assistantProperties = new AssistantProperties();
        assistantProperties.setEnabled(true);
        assistantTaskStateStore = new AssistantTaskStateStore();
        AssistantConversationSupport conversationSupport =
                new AssistantConversationSupport(assistantTaskStateStore, new ObjectMapper());
        assistantChatService = new AssistantChatService(
                assistantProperties,
                rentalAssistantProvider,
                streamingRentalAssistantProvider,
                assistantChatMemoryStoreProvider,
                rentalAssistantTools,
                assistantTaskStateStore,
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

        AssistantChatResponseVo response = assistantChatService.chat(
                "\u5e2e\u6211\u9884\u7ea6\u660e\u5929\u4e0b\u5348",
                "room-chat-001"
        );

        assertEquals("APPOINTMENT_CONFIRMING", response.getTaskState().getTaskType());
        assertEquals("WAITING_CONFIRMATION", response.getTaskState().getTaskStatus());
        assertEquals(2L, response.getTaskState().getSelectedRoomId());
        assertFalse(response.getTaskState().getProposedAppointmentTime().isBlank());
        verify(rentalAssistantProvider, never()).getIfAvailable();
    }

    @Test
    void chat_shouldUseAssistantToolExecutionsToResolveCanceledAppointmentState() {
        ToolExecution queryAppointments = toolExecution(
                "getMyAppointments",
                "{}",
                """
                        {
                          "tool":"getMyAppointments",
                          "items":[
                            {"appointmentId":13,"apartmentName":"Wendu Apartment","appointmentTime":"2026-04-11 15:00","appointmentStatusCode":1}
                          ]
                        }
                        """
        );
        ToolExecution cancelAppointment = toolExecution(
                "cancelAppointment",
                "{\"appointmentId\":13}",
                """
                        {
                          "tool":"cancelAppointment",
                          "summary":"appointment canceled",
                          "appointmentId":13,
                          "appointmentStatusCode":2,
                          "appointmentStatusText":"CANCELED",
                          "apartmentName":"Wendu Apartment",
                          "appointmentTime":"2026-04-11 15:00"
                        }
                        """
        );

        RentalAssistant rentalAssistant = mock(RentalAssistant.class);
        when(rentalAssistant.chat(anyString(), anyString())).thenReturn(
                new Result<>("appointment canceled", null, List.of(), null, List.of(queryAppointments, cancelAppointment))
        );
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(rentalAssistant);

        AssistantChatResponseVo response = assistantChatService.chat(
                "\u5e2e\u6211\u53d6\u6d88\u6700\u65b0\u9884\u7ea6",
                "room-chat-002"
        );

        assertEquals("APPOINTMENT_CANCELED", response.getTaskState().getTaskType());
        assertEquals("COMPLETED", response.getTaskState().getTaskStatus());
        assertEquals("VIEW_APPOINTMENTS", response.getNextActions().get(0).getAction());
    }

    @Test
    void chat_shouldUseAssistantToolExecutionsForAppointmentQuery() {
        ToolExecution getMyAppointments = toolExecution(
                "getMyAppointments",
                "{}",
                """
                        {
                          "tool":"getMyAppointments",
                          "summary":"found 1 appointment",
                          "items":[
                            {"appointmentId":13,"apartmentName":"Wendu Apartment","appointmentTime":"2026-04-11 15:00","appointmentStatusCode":1,"statusText":"WAITING"}
                          ]
                        }
                        """
        );
        RentalAssistant rentalAssistant = mock(RentalAssistant.class);
        when(rentalAssistant.chat(anyString(), anyString())).thenReturn(
                new Result<>("found 1 appointment", null, List.of(), null, List.of(getMyAppointments))
        );
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(rentalAssistant);

        AssistantChatResponseVo response = assistantChatService.chat(
                "\u67e5\u770b\u60a8\u7684\u6240\u6709\u9884\u7ea6\u8bb0\u5f55",
                "room-chat-003"
        );

        assertEquals("tool", response.getAnswerSource());
        assertEquals("APPOINTMENT_QUERY", response.getTaskState().getTaskType());
        assertEquals("CANCEL_LATEST_APPOINTMENT", response.getNextActions().get(0).getAction());
        assertEquals("getMyAppointments", response.getToolExecutions().get(0).getToolName());
        verify(rentalAssistantTools, never()).getMyAppointments();
    }

    @Test
    void chat_shouldUseAssistantToolExecutionsForRoomSearch() {
        ToolExecution searchRooms = toolExecution(
                "searchRooms",
                "{\"districtName\":\"Beijing\",\"maxRent\":3000}",
                """
                        {
                          "tool":"searchRooms",
                          "summary":"found 1 room",
                          "items":[
                            {"roomId":2,"title":"Wendu Room 101","locationText":"Beijing Changping","rentText":"3000/month","labels":["near subway"]}
                          ]
                        }
                        """
        );
        RentalAssistant rentalAssistant = mock(RentalAssistant.class);
        when(rentalAssistant.chat(anyString(), anyString())).thenReturn(
                new Result<>("found 1 room", null, List.of(), null, List.of(searchRooms))
        );
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(rentalAssistant);

        AssistantChatResponseVo response = assistantChatService.chat(
                "\u5e2e\u6211\u67e5\u4e00\u4e0b\u5317\u4eac\u5e023000\u4ee5\u5185\u7684\u623f\u6e90",
                "room-chat-004"
        );

        assertEquals("tool", response.getAnswerSource());
        assertEquals("ROOM_SEARCH", response.getTaskState().getTaskType());
        assertEquals("VIEW_ROOM_DETAIL", response.getNextActions().get(0).getAction());
        assertEquals("searchRooms", response.getToolExecutions().get(0).getToolName());
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
                        "\u9884\u7ea6ID 14 / Wendu Apartment / 2026-04-11 12:00",
                        null,
                        List.of()
                )
        );

        AssistantChatResponseVo response = assistantChatService.chat(
                "\u628a\u8fd9\u6761\u9884\u7ea6\u6539\u523011\u53f7\u768411\u70b9",
                "room-chat-005"
        );

        assertEquals("APPOINTMENT_RESCHEDULE_CONFIRMING", response.getTaskState().getTaskType());
        assertEquals("WAITING_CONFIRMATION", response.getTaskState().getTaskStatus());
        assertEquals(14L, response.getTaskState().getSelectedAppointmentId());
        assertEquals("CONFIRM_RESCHEDULE_APPOINTMENT", response.getNextActions().get(0).getAction());
        assertEquals("agent", response.getAnswerSource());
        assertEquals("11:00", response.getTaskState().getProposedAppointmentTime().substring(11));
        verify(rentalAssistantProvider, never()).getIfAvailable();
    }

    @Test
    void chat_shouldUseAssistantToolExecutionsForRoomDetail() {
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
                                new AssistantTaskState.RoomCandidate(2L, "Wendu Apartment 101"),
                                new AssistantTaskState.RoomCandidate(3L, "Huilongguan 102")
                        )
                )
        );

        ToolExecution getRoomDetail = toolExecution(
                "getRoomDetail",
                "{\"roomId\":2}",
                """
                        {
                          "tool":"getRoomDetail",
                          "summary":"room detail loaded",
                          "roomId":2,
                          "apartmentId":12,
                          "title":"Wendu Apartment 101",
                          "locationText":"Beijing Changping",
                          "rentText":"2500/month",
                          "labels":["south","private bathroom"]
                        }
                        """
        );
        RentalAssistant rentalAssistant = mock(RentalAssistant.class);
        when(rentalAssistant.chat(anyString(), anyString())).thenReturn(
                new Result<>("room detail loaded", null, List.of(), null, List.of(getRoomDetail))
        );
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(rentalAssistant);

        AssistantChatResponseVo response = assistantChatService.chat(
                "\u6e29\u90fd\u6c34\u57ce\u793e\u533a101\u4ecb\u7ecd\u4e00\u4e0b",
                "room-chat-006"
        );

        assertEquals("tool", response.getAnswerSource());
        assertEquals("ROOM_DETAIL", response.getTaskState().getTaskType());
        assertEquals(2L, response.getTaskState().getSelectedRoomId());
        assertEquals("ASK_APPOINTMENT", response.getNextActions().get(0).getAction());
        assertEquals("getRoomDetail", response.getToolExecutions().get(0).getToolName());
    }

    private ToolExecution toolExecution(String name, String arguments, String result) {
        ToolExecution toolExecution = mock(ToolExecution.class, RETURNS_DEEP_STUBS);
        when(toolExecution.request().name()).thenReturn(name);
        when(toolExecution.request().arguments()).thenReturn(arguments);
        when(toolExecution.result()).thenReturn(result);
        return toolExecution;
    }
}
