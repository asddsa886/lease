package com.atguigu.lease.web.admin.controller.lease;


import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.mq.publisher.LeaseAgreementEventPublisher;
import com.atguigu.lease.model.entity.LeaseAgreement;
import com.atguigu.lease.model.enums.LeaseStatus;
import com.atguigu.lease.web.admin.service.LeaseAgreementService;
import com.atguigu.lease.web.admin.vo.agreement.AgreementQueryVo;
import com.atguigu.lease.web.admin.vo.agreement.AgreementVo;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;


@Tag(name = "租约管理")
@RestController
@RequestMapping("/admin/agreement")
public class LeaseAgreementController {

    @Autowired
    private LeaseAgreementService leaseAgreementService;

    @Autowired(required = false)
    private LeaseAgreementEventPublisher leaseAgreementEventPublisher;

    @Operation(summary = "保存或修改租约信息")
    @PostMapping("saveOrUpdate")
    @Transactional(rollbackFor = Exception.class)
    public Result saveOrUpdate(@RequestBody LeaseAgreement leaseAgreement) {
        boolean created = leaseAgreement != null && leaseAgreement.getId() == null;
        leaseAgreementService.saveOrUpdate(leaseAgreement);

        if (leaseAgreementEventPublisher != null && leaseAgreement != null) {
            leaseAgreementEventPublisher.publishUpsert(
                    leaseAgreement.getId(),
                    leaseAgreement.getPhone(),
                    leaseAgreement.getStatus() == null ? null : leaseAgreement.getStatus().name(),
                    created
            );
        }
        return Result.ok();
    }

    @Operation(summary = "根据条件分页查询租约列表")
    @GetMapping("page")
    public Result<IPage<AgreementVo>> page(@RequestParam long current, @RequestParam long size, AgreementQueryVo queryVo) {
        Page<AgreementVo> page = new Page<>(current, size);
        IPage<AgreementVo> result = leaseAgreementService.pageAgreement(page,queryVo);
        return Result.ok(result);
    }

    @Operation(summary = "根据id查询租约信息")
    @GetMapping(name = "getById")
    public Result<AgreementVo> getById(@RequestParam Long id) {
        AgreementVo apartment = leaseAgreementService.getAgreementById(id);
        return Result.ok(apartment);
    }

    @Operation(summary = "根据id删除租约信息")
    @DeleteMapping("removeById")
    public Result removeById(@RequestParam Long id) {
        leaseAgreementService.removeById(id);

        return Result.ok();
    }

    @Operation(summary = "根据id更新租约状态")
    @PostMapping("updateStatusById")
    @Transactional(rollbackFor = Exception.class)
    public Result updateStatusById(@RequestParam Long id, @RequestParam LeaseStatus status) {
        LeaseAgreement db = leaseAgreementService.getById(id);
        LeaseStatus before = db == null ? null : db.getStatus();
        String phone = db == null ? null : db.getPhone();

        LambdaUpdateWrapper<LeaseAgreement> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(LeaseAgreement::getId, id);
        updateWrapper.set(LeaseAgreement::getStatus, status);
        leaseAgreementService.update(updateWrapper);

        if (leaseAgreementEventPublisher != null) {
            leaseAgreementEventPublisher.publishStatusChanged(id, phone,
                    before == null ? null : before.name(),
                    status == null ? null : status.name());
        }
        return Result.ok();
    }

}

