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

    @Tool(description = "查询当前用户的预约列表")
    public AssistantToolResult listMyAppointments(ToolContext toolContext) {
        return executeTool("listMyAppointments", toolContext, "预约列表查询成功",
                () -> toAppointmentItems(viewAppointmentService.getDetailByUserId(currentUserId(toolContext))));
    }

    @Tool(description = "为当前用户创建看房预约")
    public AssistantToolResult createAppointment(@ToolParam(description = "公寓ID", required = true) Long apartmentId,
                                                 @ToolParam(description = "预约时间，按中国时区本地时间理解。支持 yyyy-MM-dd HH:mm:ss，也支持“明天下午12点”“周六下午3点”这类自然语言，禁止转换成 UTC。", required = true) String appointmentTime,
                                                 @ToolParam(description = "补充说明") String additionalInfo,
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
            return toAppointmentRecord(appointment);
        });
    }

    @Tool(description = "取消当前用户的预约")
    public AssistantToolResult cancelAppointment(@ToolParam(description = "预约ID", required = true) Long appointmentId,
                                                 ToolContext toolContext) {
        return executeTool("cancelAppointment", toolContext, "预约取消成功",
                () -> toAppointmentRecord(viewAppointmentService.cancelForCurrentUser(appointmentId, currentUserId(toolContext))));
    }

    @Tool(description = "修改当前用户的预约时间")
    public AssistantToolResult rescheduleAppointment(@ToolParam(description = "预约ID", required = true) Long appointmentId,
                                                     @ToolParam(description = "新的预约时间，按中国时区本地时间理解。支持 yyyy-MM-dd HH:mm:ss，也支持“明天下午12点”“周六下午3点”这类自然语言，禁止转换成 UTC。", required = true) String appointmentTime,
                                                     ToolContext toolContext) {
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
