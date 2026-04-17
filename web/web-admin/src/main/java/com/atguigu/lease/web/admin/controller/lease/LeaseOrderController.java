package com.atguigu.lease.web.admin.controller.lease;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.web.admin.service.LeaseOrderService;
import com.atguigu.lease.web.admin.vo.order.LeaseOrderQueryVo;
import com.atguigu.lease.web.admin.vo.order.LeaseOrderVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "签约订单管理")
@RestController
@RequestMapping("/admin/order")
public class LeaseOrderController {

    private final LeaseOrderService leaseOrderService;

    public LeaseOrderController(LeaseOrderService leaseOrderService) {
        this.leaseOrderService = leaseOrderService;
    }

    @Operation(summary = "分页查询签约订单")
    @GetMapping("page")
    public Result<IPage<LeaseOrderVo>> page(@RequestParam long current,
                                            @RequestParam long size,
                                            LeaseOrderQueryVo queryVo) {
        IPage<LeaseOrderVo> result = leaseOrderService.pageOrder(new Page<>(current, size), queryVo);
        return Result.ok(result);
    }

    @Operation(summary = "根据id查询签约订单")
    @GetMapping("getById")
    public Result<LeaseOrderVo> getById(@RequestParam Long id) {
        return Result.ok(leaseOrderService.getOrderById(id));
    }

    @Operation(summary = "根据id确认已支付签约订单")
    @PostMapping("confirmById")
    public Result<Void> confirmById(@RequestParam Long id) {
        leaseOrderService.confirmById(id);
        return Result.ok();
    }

    @Operation(summary = "根据id取消未支付签约订单")
    @PostMapping("cancelById")
    public Result<Void> cancelById(@RequestParam Long id) {
        leaseOrderService.cancelById(id);
        return Result.ok();
    }
}
