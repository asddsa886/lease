package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.model.entity.UserInfo;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.model.enums.AppointmentStatus;
import com.atguigu.lease.web.app.service.UserInfoService;
import com.atguigu.lease.web.app.service.ViewAppointmentService;
import com.atguigu.lease.web.app.vo.appointment.AppointmentItemVo;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AssistantAppointmentTools extends AbstractAssistantTools {

    private final ViewAppointmentService viewAppointmentService;
    private final UserInfoService userInfoService;

    public AssistantAppointmentTools(ViewAppointmentService viewAppointmentService,
                                     UserInfoService userInfoService) {
        this.viewAppointmentService = viewAppointmentService;
        this.userInfoService = userInfoService;
    }

    @Tool(description = "Query the current user's appointment list.")
    public AssistantToolResult listMyAppointments(ToolContext toolContext) {
        return executeTool("listMyAppointments", toolContext, "预约列表查询成功",
                () -> toAppointmentItems(viewAppointmentService.getDetailByUserId(currentUserId(toolContext))));
    }

    @Tool(description = "Create a viewing appointment for the current user. Must ask for natural-language confirmation first, then call with confirmed=true.")
    public AssistantToolResult createAppointment(@ToolParam(description = "Apartment id", required = true) Long apartmentId,
                                                 @ToolParam(description = "Appointment time in China local time, yyyy-MM-dd HH:mm:ss or natural language already parsed by the assistant", required = true) String appointmentTime,
                                                 @ToolParam(description = "Additional notes") String additionalInfo,
                                                 @ToolParam(description = "Only true after the user clearly confirms this write action in natural language", required = true) Boolean confirmed,
                                                 ToolContext toolContext) {
        if (!Boolean.TRUE.equals(confirmed)) {
            return AssistantToolResult.fail("请先让用户明确确认预约操作，再调用 createAppointment");
        }
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
            return toAppointmentRecord(appointment);
        });
    }

    @Tool(description = "Cancel the current user's appointment. Must ask for natural-language confirmation first, then call with confirmed=true.")
    public AssistantToolResult cancelAppointment(@ToolParam(description = "Appointment id", required = true) Long appointmentId,
                                                 @ToolParam(description = "Only true after the user clearly confirms this write action in natural language", required = true) Boolean confirmed,
                                                 ToolContext toolContext) {
        if (!Boolean.TRUE.equals(confirmed)) {
            return AssistantToolResult.fail("请先让用户明确确认取消预约操作，再调用 cancelAppointment");
        }
        return executeTool("cancelAppointment", toolContext, "预约取消成功",
                () -> toAppointmentRecord(viewAppointmentService.cancelForCurrentUser(appointmentId, currentUserId(toolContext))));
    }

    @Tool(description = "Reschedule the current user's appointment. Must ask for natural-language confirmation first, then call with confirmed=true.")
    public AssistantToolResult rescheduleAppointment(@ToolParam(description = "Appointment id", required = true) Long appointmentId,
                                                     @ToolParam(description = "New appointment time in China local time", required = true) String appointmentTime,
                                                     @ToolParam(description = "Only true after the user clearly confirms this write action in natural language", required = true) Boolean confirmed,
                                                     ToolContext toolContext) {
        if (!Boolean.TRUE.equals(confirmed)) {
            return AssistantToolResult.fail("请先让用户明确确认改期预约操作，再调用 rescheduleAppointment");
        }
        return executeTool("rescheduleAppointment", toolContext, "预约改期成功",
                () -> toAppointmentRecord(viewAppointmentService.rescheduleForCurrentUser(
                        appointmentId,
                        parseDateTime(appointmentTime),
                        currentUserId(toolContext)
                )));
    }

    private List<Map<String, Object>> toAppointmentItems(List<AppointmentItemVo> items) {
        return items == null ? List.of() : items.stream().map(this::toAppointmentItem).toList();
    }

    private Map<String, Object> toAppointmentItem(AppointmentItemVo item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appointmentId", item.getId());
        result.put("apartmentId", item.getApartmentId());
        result.put("apartmentName", item.getApartmentName());
        result.put("appointmentTime", formatDateTime(item.getAppointmentTime()));
        result.put("appointmentStatus", statusName(item.getAppointmentStatus()));
        return result;
    }

    private Map<String, Object> toAppointmentRecord(ViewAppointment appointment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appointmentId", appointment.getId());
        result.put("apartmentId", appointment.getApartmentId());
        result.put("appointmentTime", formatDateTime(appointment.getAppointmentTime()));
        result.put("appointmentStatus", statusName(appointment.getAppointmentStatus()));
        result.put("additionalInfo", appointment.getAdditionalInfo());
        return result;
    }

    private String statusName(AppointmentStatus status) {
        return status == null ? null : status.getName();
    }
}
