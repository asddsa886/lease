package com.atguigu.lease.web.admin.controller.apartment;


import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.web.admin.service.PaymentTypeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.atguigu.lease.common.utils.PageParamUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Tag(name = "支付方式管理")
@RequestMapping("/admin/payment")
@RestController
public class PaymentTypeController {

    @Autowired
    private PaymentTypeService paymentTypeService;
    @Operation(summary = "查询全部支付方式列表")
    @GetMapping("list")
    public Result<List<PaymentType>> listPaymentType() {
        LambdaQueryWrapper<PaymentType> queryWrapper = new LambdaQueryWrapper<PaymentType>();
        queryWrapper.eq(PaymentType::getIsDeleted, 0);
        List<PaymentType> list = paymentTypeService.list(queryWrapper);
        return Result.ok(list);
    }

    @Operation(summary = "分页查询支付方式列表")
    @GetMapping("page")
    public Result<IPage<PaymentType>> page(@RequestParam(required = false) Long current,
                                          @RequestParam(required = false) Long size) {
        LambdaQueryWrapper<PaymentType> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PaymentType::getIsDeleted, 0);
        Page<PaymentType> page = new Page<>(PageParamUtils.current(current), PageParamUtils.size(size));
        return Result.ok(paymentTypeService.page(page, queryWrapper));
    }

    @Operation(summary = "保存或更新支付方式")
    @PostMapping("saveOrUpdate")
    public Result saveOrUpdatePaymentType(@RequestBody PaymentType paymentType) {
        boolean isSuccess = paymentTypeService.saveOrUpdate(paymentType);
        if (isSuccess) {
            return Result.ok();
        }else {
            return Result.fail();
        }
    }

    @Operation(summary = "根据ID删除支付方式")
    @DeleteMapping("deleteById")
    public Result deletePaymentById(@RequestParam Long id) {
        boolean isSuccess = paymentTypeService.removeById(id);
        if (isSuccess) {
            return Result.ok();
        } else {
            return Result.fail();
        }
    }

}















