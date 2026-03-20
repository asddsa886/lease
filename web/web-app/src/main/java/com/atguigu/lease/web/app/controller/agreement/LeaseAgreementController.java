package com.atguigu.lease.web.app.controller.agreement;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.LeaseAgreement;
import com.atguigu.lease.model.enums.LeaseStatus;
import com.atguigu.lease.web.app.service.LeaseAgreementService;
import com.atguigu.lease.web.app.vo.agreement.AgreementDetailVo;
import com.atguigu.lease.web.app.vo.agreement.AgreementItemVo;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/app/agreement")
@Validated
@Tag(name = "租约信息")
public class LeaseAgreementController {

    @Autowired
    private LeaseAgreementService leaseAgreementService;

    @Operation(summary = "获取个人租约基本信息列表")
    @GetMapping("listItem")
    public Result<List<AgreementItemVo>> listItem() {
        String username = LoginUserHolder.get().getUsername();
        List<AgreementItemVo> result = leaseAgreementService.listItemByPhone(username);
        return Result.ok(result);
    }

    @Operation(summary = "根据id获取租约详细信息")
    @GetMapping("getDetailById")
    public Result<AgreementDetailVo> getDetailById(@RequestParam @NotNull(message = "id不能为空")
                                                   @Min(value = 1, message = "id必须>=1") Long id) {
        AgreementDetailVo result = leaseAgreementService.getDetailById(id);
        return Result.ok(result);
    }

    @Operation(summary = "根据id更新租约状态", description = "用于确认租约和提前退租")
    @PostMapping("updateStatusById")
    public Result updateStatusById(@RequestParam @NotNull(message = "id不能为空")
                                   @Min(value = 1, message = "id必须>=1") Long id,
                                   @RequestParam @NotNull(message = "leaseStatus不能为空") LeaseStatus leaseStatus) {
        LeaseAgreement leaseAgreement = leaseAgreementService.getById(id);
        if (leaseAgreement == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }

        // IDOR/越权防护：只能操作自己的租约
        String currentUserPhone = LoginUserHolder.get().getUsername();
        if (currentUserPhone == null || !currentUserPhone.equals(leaseAgreement.getPhone())) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        // 最小化状态机：仅允许“确认签约 / 发起退租”
        LeaseStatus currentStatus = leaseAgreement.getStatus();
        boolean allowed =
                (currentStatus == LeaseStatus.SIGNING && leaseStatus == LeaseStatus.SIGNED)
                        || (currentStatus == LeaseStatus.SIGNED && leaseStatus == LeaseStatus.WITHDRAWING);

        if (!allowed) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        // 条件更新：防止并发/重复提交（状态已变化则更新失败）
        LambdaUpdateWrapper<LeaseAgreement> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(LeaseAgreement::getId, id);
        updateWrapper.eq(LeaseAgreement::getPhone, currentUserPhone);
        updateWrapper.eq(LeaseAgreement::getStatus, currentStatus);
        updateWrapper.set(LeaseAgreement::getStatus, leaseStatus);

        boolean updated = leaseAgreementService.update(updateWrapper);
        if (!updated) {
            throw new LeaseException(ResultCodeEnum.REPEAT_SUBMIT);
        }
        return Result.ok();
    }

    @Operation(summary = "保存或更新租约", description = "用于续约")
    @PostMapping("saveOrUpdate")
    public Result saveOrUpdate(@RequestBody @NotNull(message = "leaseAgreement不能为空") LeaseAgreement leaseAgreement) {
        // 安全加固：C 端不允许“新增租约”，只允许对自己的租约发起续约更新
        if (leaseAgreement.getId() == null) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        LeaseAgreement dbAgreement = leaseAgreementService.getById(leaseAgreement.getId());
        if (dbAgreement == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }

        String currentUserPhone = LoginUserHolder.get().getUsername();
        if (currentUserPhone == null || !currentUserPhone.equals(dbAgreement.getPhone())) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        // 仅允许已签约的租约发起续约（避免乱改历史状态）
        if (dbAgreement.getStatus() != LeaseStatus.SIGNED) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }

        // 防篡改：敏感字段以 DB 为准
        leaseAgreement.setPhone(dbAgreement.getPhone());
        leaseAgreement.setName(dbAgreement.getName());
        leaseAgreement.setIdentificationNumber(dbAgreement.getIdentificationNumber());
        leaseAgreement.setApartmentId(dbAgreement.getApartmentId());
        leaseAgreement.setRoomId(dbAgreement.getRoomId());
        leaseAgreement.setLeaseStartDate(dbAgreement.getLeaseStartDate());
        leaseAgreement.setRent(dbAgreement.getRent());
        leaseAgreement.setDeposit(dbAgreement.getDeposit());
        leaseAgreement.setSourceType(dbAgreement.getSourceType());

        // 续约进入待确认状态
        leaseAgreement.setStatus(LeaseStatus.RENEWING);

        leaseAgreementService.updateById(leaseAgreement);
        return Result.ok();
    }

}
