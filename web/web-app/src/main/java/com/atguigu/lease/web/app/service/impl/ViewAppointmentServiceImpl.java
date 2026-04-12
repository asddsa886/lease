package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.model.enums.AppointmentStatus;
import com.atguigu.lease.web.app.mapper.ViewAppointmentMapper;
import com.atguigu.lease.web.app.service.ViewAppointmentService;
import com.atguigu.lease.web.app.vo.appointment.AppointmentItemVo;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author liubo
 * @description 针对表【view_appointment(预约看房信息表)】的数据库操作Service实现
 * @createDate 2023-07-26 11:12:39
 */
@Service
public class ViewAppointmentServiceImpl extends ServiceImpl<ViewAppointmentMapper, ViewAppointment>
        implements ViewAppointmentService {

    @Autowired
    private ViewAppointmentMapper viewAppointmentMapper;

    @Override
    public List<AppointmentItemVo> getDetailByUserId(Long id) {
        List<AppointmentItemVo> items = viewAppointmentMapper.getDetailByUserId(id);
        if (items == null || items.isEmpty()) {
            return items;
        }

        // 批量查询图片并按 apartmentId 分组，避免 N+1
        List<Long> apartmentIds = items.stream()
                .map(AppointmentItemVo::getApartmentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (apartmentIds.isEmpty()) {
            return items;
        }

        List<GraphVo> graphs = viewAppointmentMapper.listApartmentGraphsByApartmentIds(apartmentIds);
        Map<Long, List<GraphVo>> graphMap = (graphs == null ? Collections.<GraphVo>emptyList() : graphs)
                .stream()
                .filter(g -> g.getApartmentId() != null)
                .collect(Collectors.groupingBy(GraphVo::getApartmentId));

        for (AppointmentItemVo item : items) {
            if (item.getApartmentId() == null) {
                item.setGraphVoList(Collections.emptyList());
                continue;
            }
            item.setGraphVoList(graphMap.getOrDefault(item.getApartmentId(), Collections.emptyList()));
        }
        return items;
    }

    @Override
    public void saveOrUpdateForCurrentUser(ViewAppointment viewAppointment, Long currentUserId) {
        if (currentUserId == null || viewAppointment == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        // 基础必填校验（最小集）
        if (viewAppointment.getApartmentId() == null || viewAppointment.getAppointmentTime() == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        // 1) 新增：不允许客户端指定 id / status / userId
        if (viewAppointment.getId() == null) {
            viewAppointment.setUserId(currentUserId);
            viewAppointment.setAppointmentStatus(AppointmentStatus.WAITING);
            this.save(viewAppointment);
            return;
        }

        // 2) 更新：先查库，校验所有权；并禁止篡改敏感字段
        ViewAppointment db = this.getById(viewAppointment.getId());
        if (db == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (!currentUserId.equals(db.getUserId())) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        // 防篡改：归属字段 / 状态 / 公寓归属以 DB 为准
        viewAppointment.setUserId(db.getUserId());
        viewAppointment.setAppointmentStatus(db.getAppointmentStatus());
        viewAppointment.setApartmentId(db.getApartmentId());

        this.updateById(viewAppointment);
    }

    @Override
    public ViewAppointment cancelForCurrentUser(Long appointmentId, Long currentUserId) {
        if (appointmentId == null || currentUserId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        ViewAppointment db = this.getById(appointmentId);
        if (db == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (!currentUserId.equals(db.getUserId())) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }
        if (db.getAppointmentStatus() == AppointmentStatus.WAITING) {
            db.setAppointmentStatus(AppointmentStatus.CANCELED);
            this.updateById(db);
        }
        return db;
    }

    @Override
    public ViewAppointment rescheduleForCurrentUser(Long appointmentId, Date appointmentTime, Long currentUserId) {
        if (appointmentId == null || appointmentTime == null || currentUserId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        ViewAppointment db = this.getById(appointmentId);
        if (db == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (!currentUserId.equals(db.getUserId())) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }
        if (db.getAppointmentStatus() != AppointmentStatus.WAITING) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        db.setAppointmentTime(appointmentTime);
        this.updateById(db);
        return db;
    }
}




