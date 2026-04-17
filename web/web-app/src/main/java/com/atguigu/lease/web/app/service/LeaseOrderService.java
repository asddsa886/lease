package com.atguigu.lease.web.app.service;

import com.atguigu.lease.model.entity.LeaseOrder;
import com.atguigu.lease.web.app.vo.order.LeaseOrderDetailVo;
import com.atguigu.lease.web.app.vo.order.LeaseOrderItemVo;
import com.atguigu.lease.web.app.vo.order.LeaseOrderSubmitVo;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface LeaseOrderService extends IService<LeaseOrder> {

    Long submit(LeaseOrderSubmitVo submitVo, Long currentUserId);

    void payById(Long id, Long currentUserId);

    List<LeaseOrderItemVo> listItemByCurrentUser(Long currentUserId);

    LeaseOrderDetailVo getDetailById(Long id, Long currentUserId);

    void cancelById(Long id, Long currentUserId);
}
