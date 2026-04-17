package com.atguigu.lease.web.admin.service;

import com.atguigu.lease.model.entity.LeaseOrder;
import com.atguigu.lease.web.admin.vo.order.LeaseOrderQueryVo;
import com.atguigu.lease.web.admin.vo.order.LeaseOrderVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

public interface LeaseOrderService extends IService<LeaseOrder> {

    IPage<LeaseOrderVo> pageOrder(Page<LeaseOrder> page, LeaseOrderQueryVo queryVo);

    LeaseOrderVo getOrderById(Long id);

    void confirmById(Long id);

    void cancelById(Long id);
}
