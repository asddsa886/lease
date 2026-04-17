package com.atguigu.lease.web.app.controller.order;

import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.web.app.service.LeaseOrderService;
import com.atguigu.lease.web.app.vo.order.LeaseOrderDetailVo;
import com.atguigu.lease.web.app.vo.order.LeaseOrderItemVo;
import com.atguigu.lease.web.app.vo.order.LeaseOrderSubmitVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/app/order")
@Tag(name = "签约订单")
public class LeaseOrderController {

    private final LeaseOrderService leaseOrderService;

    public LeaseOrderController(LeaseOrderService leaseOrderService) {
        this.leaseOrderService = leaseOrderService;
    }

    @Operation(summary = "提交签约订单")
    @PostMapping("submit")
    public Result<Long> submit(@RequestBody @Valid LeaseOrderSubmitVo submitVo) {
        Long id = leaseOrderService.submit(submitVo, LoginUserHolder.get().getId());
        return Result.ok(id);
    }

    @Operation(summary = "根据id模拟支付签约订单")
    @PostMapping("payById")
    public Result<Void> payById(@RequestParam @NotNull(message = "id不能为空")
                                @Min(value = 1, message = "id必须大于等于1") Long id) {
        leaseOrderService.payById(id, LoginUserHolder.get().getId());
        return Result.ok();
    }

    @Operation(summary = "查询当前用户签约订单列表")
    @GetMapping("listItem")
    public Result<List<LeaseOrderItemVo>> listItem() {
        List<LeaseOrderItemVo> result = leaseOrderService.listItemByCurrentUser(LoginUserHolder.get().getId());
        return Result.ok(result);
    }

    @Operation(summary = "根据id查询签约订单详情")
    @GetMapping("getDetailById")
    public Result<LeaseOrderDetailVo> getDetailById(@RequestParam @NotNull(message = "id不能为空")
                                                    @Min(value = 1, message = "id必须大于等于1") Long id) {
        LeaseOrderDetailVo result = leaseOrderService.getDetailById(id, LoginUserHolder.get().getId());
        return Result.ok(result);
    }

    @Operation(summary = "根据id取消未支付签约订单")
    @PostMapping("cancelById")
    public Result<Void> cancelById(@RequestParam @NotNull(message = "id不能为空")
                                   @Min(value = 1, message = "id必须大于等于1") Long id) {
        leaseOrderService.cancelById(id, LoginUserHolder.get().getId());
        return Result.ok();
    }
}
