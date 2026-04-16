package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskState;
import com.atguigu.lease.web.app.chat.agent.AssistantTaskStateStore;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.memory.AssistantMongoChatMemoryStore;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
                assistantTaskStateStore,
                conversationSupport
        );
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    void chat_shouldUseAssistantToolExecutionsForAppointmentCreateInsteadOfLocalStateMachine() {
        assistantTaskStateStore.save(
                "room-chat-001",
                new AssistantTaskState("ROOM_DETAIL", "WAITING_USER_INPUT", 2L, "Huilongguan Room 102", 12L, null, List.of())
        );

        ToolExecution createRoomAppointment = toolExecution(
                "createRoomAppointment",
                "{\"roomId\":2,\"appointmentTimeText\":\"明天下午\"}",
                """
                        {
                          "tool":"createRoomAppointment",
                          "summary":"appointment created",
                          "appointmentId":16,
                          "appointmentTime":"2026-04-17 15:00",
                          "roomId":2,
                          "apartmentId":12,
                          "title":"Huilongguan Room 102"
                        }
                        """
        );
        RentalAssistant rentalAssistant = mock(RentalAssistant.class);
        when(rentalAssistant.chat(anyString(), anyString())).thenReturn(
                new Result<>("appointment created", null, List.of(), null, List.of(createRoomAppointment))
        );
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(rentalAssistant);

        AssistantChatResponseVo response = assistantChatService.chat(
                "帮我预约明天下午",
                "room-chat-001"
        );

        assertEquals("tool", response.getAnswerSource());
        assertEquals("APPOINTMENT_CREATED", response.getTaskState().getTaskType());
        assertEquals("COMPLETED", response.getTaskState().getTaskStatus());
        assertEquals(16L, response.getTaskState().getSelectedAppointmentId());
        verify(rentalAssistantProvider, times(1)).getIfAvailable();
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
                "帮我取消最新预约",
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
                "查看您的所有预约记录",
                "room-chat-003"
        );

        assertEquals("tool", response.getAnswerSource());
        assertEquals("APPOINTMENT_QUERY", response.getTaskState().getTaskType());
        assertEquals("CANCEL_LATEST_APPOINTMENT", response.getNextActions().get(0).getAction());
        assertEquals("getMyAppointments", response.getToolExecutions().get(0).getToolName());
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
                "帮我查一下北京市3000以内的房源",
                "room-chat-004"
        );

        assertEquals("tool", response.getAnswerSource());
        assertEquals("ROOM_SEARCH", response.getTaskState().getTaskType());
        assertEquals("VIEW_ROOM_DETAIL", response.getNextActions().get(0).getAction());
        assertEquals("searchRooms", response.getToolExecutions().get(0).getToolName());
    }

    @Test
    void chat_shouldUseAssistantToolExecutionsForAppointmentRescheduleWithoutLocalConfirmation() {
        assistantTaskStateStore.save(
                "room-chat-005",
                new AssistantTaskState(
                        "APPOINTMENT_QUERY",
                        "COMPLETED",
                        null,
                        null,
                        null,
                        14L,
                        "预约ID 14 / Wendu Apartment / 2026-04-11 12:00",
                        null,
                        List.of()
                )
        );

        ToolExecution rescheduleAppointment = toolExecution(
                "rescheduleAppointment",
                "{\"appointmentId\":14,\"appointmentTimeText\":\"11号11点\"}",
                """
                        {
                          "tool":"rescheduleAppointment",
                          "summary":"appointment rescheduled",
                          "appointmentId":14,
                          "appointmentStatusCode":1,
                          "appointmentStatusText":"WAITING",
                          "apartmentName":"Wendu Apartment",
                          "appointmentTime":"2026-04-11 11:00"
                        }
                        """
        );
        RentalAssistant rentalAssistant = mock(RentalAssistant.class);
        when(rentalAssistant.chat(anyString(), anyString())).thenReturn(
                new Result<>("appointment rescheduled", null, List.of(), null, List.of(rescheduleAppointment))
        );
        when(rentalAssistantProvider.getIfAvailable()).thenReturn(rentalAssistant);

        AssistantChatResponseVo response = assistantChatService.chat(
                "把这条预约改到11号的11点",
                "room-chat-005"
        );

        assertEquals("tool", response.getAnswerSource());
        assertEquals("APPOINTMENT_RESCHEDULED", response.getTaskState().getTaskType());
        assertEquals("COMPLETED", response.getTaskState().getTaskStatus());
        assertEquals(14L, response.getTaskState().getSelectedAppointmentId());
        assertEquals("VIEW_APPOINTMENTS", response.getNextActions().get(0).getAction());
        verify(rentalAssistantProvider, times(1)).getIfAvailable();
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
                "温都水城社区101介绍一下",
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
