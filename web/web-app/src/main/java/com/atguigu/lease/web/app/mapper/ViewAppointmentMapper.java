package com.atguigu.lease.web.app.mapper;

import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.web.app.vo.appointment.AppointmentItemVo;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
* @author liubo
* @description 针对表【view_appointment(预约看房信息表)】的数据库操作Mapper
* @createDate 2023-07-26 11:12:39
* @Entity com.atguigu.lease.model.entity.ViewAppointment
*/
public interface ViewAppointmentMapper extends BaseMapper<ViewAppointment> {

    List<AppointmentItemVo> getDetailByUserId(Long id);

    /**
     * 批量查询预约列表对应的公寓图片（避免 resultMap + nested select 导致 N+1）
     */
    List<GraphVo> listApartmentGraphsByApartmentIds(@Param("apartmentIds") List<Long> apartmentIds);
}




