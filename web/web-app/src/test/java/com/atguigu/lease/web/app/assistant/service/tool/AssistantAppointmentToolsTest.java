package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.model.entity.UserInfo;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.model.enums.AppointmentStatus;
import com.atguigu.lease.web.app.service.UserInfoService;
import com.atguigu.lease.web.app.service.ViewAppointmentService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantAppointmentToolsTest {

    @Test
    void shouldReturnLocalAppointmentTimeTextForCreateAppointment() {
        ViewAppointmentService viewAppointmentService = mock(ViewAppointmentService.class);
        UserInfoService userInfoService = mock(UserInfoService.class);
        AssistantAppointmentTools tools = new AssistantAppointmentTools(viewAppointmentService, userInfoService);

        UserInfo userInfo = new UserInfo();
        userInfo.setPhone("17503976585");
        userInfo.setNickname("测试用户");
        when(userInfoService.getById(8L)).thenReturn(userInfo);

        doAnswer(invocation -> {
            ViewAppointment appointment = invocation.getArgument(0);
            appointment.setId(100L);
            appointment.setAppointmentStatus(AppointmentStatus.WAITING);
            return null;
        }).when(viewAppointmentService).saveOrUpdateForCurrentUser(any(ViewAppointment.class), eq(8L));

        ToolContext toolContext = new ToolContext(Map.of(
                AssistantToolContextSupport.CURRENT_USER_ID, 8L,
                AssistantToolContextSupport.TOOL_EVENT_EMITTER, AssistantToolEventEmitter.noop()
        ));

        AssistantToolResult result = tools.createAppointment(9L, "2026-04-21 12:00:00", "测试预约", toolContext);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertThat(data.get("appointmentId")).isEqualTo(100L);
        assertThat(data.get("appointmentTime")).isEqualTo("2026-04-21 12:00:00");
        assertThat(data.get("appointmentStatus")).isEqualTo(AppointmentStatus.WAITING.getName());
    }
}
