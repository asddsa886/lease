package com.atguigu.lease.web.app.vo.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

@Data
@Schema(description = "用户端签约订单提交参数")
public class LeaseOrderSubmitVo {

    @Schema(description = "房间id")
    @NotNull(message = "roomId不能为空")
    @Min(value = 1, message = "roomId必须大于等于1")
    private Long roomId;

    @Schema(description = "租期id")
    @NotNull(message = "leaseTermId不能为空")
    @Min(value = 1, message = "leaseTermId必须大于等于1")
    private Long leaseTermId;

    @Schema(description = "支付方式id")
    @NotNull(message = "paymentTypeId不能为空")
    @Min(value = 1, message = "paymentTypeId必须大于等于1")
    private Long paymentTypeId;

    @Schema(description = "租约开始日期")
    @NotNull(message = "leaseStartDate不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date leaseStartDate;

    @Schema(description = "备注信息")
    private String additionalInfo;
}
