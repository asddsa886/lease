package com.atguigu.lease.web.app.service;

import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.web.app.vo.appointment.AppointmentItemVo;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
* @author liubo
* @description 针对表【view_appointment(预约看房信息表)】的数据库操作Service
* @createDate 2023-07-26 11:12:39
*/
public interface ViewAppointmentService extends IService<ViewAppointment> {

    List<AppointmentItemVo> getDetailByUserId(Long id);

    /**
     * 安全加固：只允许当前用户创建/修改自己的预约，禁止越权修改他人预约、禁止篡改状态/归属字段。
     */
    void saveOrUpdateForCurrentUser(ViewAppointment viewAppointment, Long currentUserId);

    ViewAppointment cancelForCurrentUser(Long appointmentId, Long currentUserId);

    ViewAppointment rescheduleForCurrentUser(Long appointmentId, java.util.Date appointmentTime, Long currentUserId);
}
