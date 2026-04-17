package com.atguigu.lease.web.app.vo.order;

import com.atguigu.lease.model.entity.ApartmentInfo;
import com.atguigu.lease.model.entity.LeaseAgreement;
import com.atguigu.lease.model.entity.LeaseOrder;
import com.atguigu.lease.model.entity.LeaseTerm;
import com.atguigu.lease.model.entity.PaymentType;
import com.atguigu.lease.model.entity.RoomInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "用户端签约订单详情")
public class LeaseOrderDetailVo extends LeaseOrder {

    @Schema(description = "公寓信息")
    private ApartmentInfo apartmentInfo;

    @Schema(description = "房间信息")
    private RoomInfo roomInfo;

    @Schema(description = "支付方式")
    private PaymentType paymentType;

    @Schema(description = "租期信息")
    private LeaseTerm leaseTerm;

    @Schema(description = "租约信息")
    private LeaseAgreement agreementInfo;
}
