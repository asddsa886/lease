package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.model.entity.UserInfo;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.web.app.service.UserInfoService;
import com.atguigu.lease.web.app.service.ViewAppointmentService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AssistantAppointmentTools extends AbstractAssistantTools {

    private final ViewAppointmentService viewAppointmentService;
    private final UserInfoService userInfoService;

    public AssistantAppointmentTools(ViewAppointmentService viewAppointmentService,
                                     UserInfoService userInfoService) {
        this.viewAppointmentService = viewAppointmentService;
        this.userInfoService = userInfoService;
    }

    @Tool(description = "List appointments of current user.")
    public AssistantToolResult listMyAppointments(ToolContext toolContext) {
        return executeTool("listMyAppointments", toolContext, "预约列表查询成功",
                () -> viewAppointmentService.getDetailByUserId(currentUserId(toolContext)));
    }

    @Tool(description = "Create an apartment viewing appointment for current user.")
    public AssistantToolResult createAppointment(@ToolParam(description = "Apartment id", required = true) Long apartmentId,
                                                 @ToolParam(description = "Appointment time in yyyy-MM-dd HH:mm:ss format", required = true) String appointmentTime,
                                                 @ToolParam(description = "Additional note") String additionalInfo,
                                                 ToolContext toolContext) {
        return executeTool("createAppointment", toolContext, "预约创建成功", () -> {
            Long userId = currentUserId(toolContext);
            UserInfo userInfo = userInfoService.getById(userId);

            ViewAppointment appointment = new ViewAppointment();
            appointment.setApartmentId(apartmentId);
            appointment.setAppointmentTime(parseDateTime(appointmentTime));
            appointment.setAdditionalInfo(additionalInfo);
            appointment.setUserId(userId);
            appointment.setPhone(userInfo.getPhone());
            appointment.setName(userInfo.getNickname() == null || userInfo.getNickname().isBlank()
                    ? userInfo.getPhone()
                    : userInfo.getNickname());

            viewAppointmentService.saveOrUpdateForCurrentUser(appointment, userId);
            return appointment;
        });
    }

    @Tool(description = "Cancel an appointment of current user.")
    public AssistantToolResult cancelAppointment(@ToolParam(description = "Appointment id", required = true) Long appointmentId,
                                                 ToolContext toolContext) {
        return executeTool("cancelAppointment", toolContext, "预约取消成功",
                () -> viewAppointmentService.cancelForCurrentUser(appointmentId, currentUserId(toolContext)));
    }

    @Tool(description = "Reschedule an appointment of current user.")
    public AssistantToolResult rescheduleAppointment(@ToolParam(description = "Appointment id", required = true) Long appointmentId,
                                                     @ToolParam(description = "New appointment time in yyyy-MM-dd HH:mm:ss format", required = true) String appointmentTime,
                                                     ToolContext toolContext) {
        return executeTool("rescheduleAppointment", toolContext, "预约改约成功",
                () -> viewAppointmentService.rescheduleForCurrentUser(
                        appointmentId,
                        parseDateTime(appointmentTime),
                        currentUserId(toolContext)
                ));
    }
}
