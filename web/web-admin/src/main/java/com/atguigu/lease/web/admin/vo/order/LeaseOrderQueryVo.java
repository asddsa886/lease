package com.atguigu.lease.web.admin.vo.order;

import com.atguigu.lease.model.enums.LeaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理端签约订单查询条件")
public class LeaseOrderQueryVo {

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "订单状态")
    private LeaseOrderStatus status;

    @Schema(description = "公寓id")
    private Long apartmentId;

    @Schema(description = "房间id")
    private Long roomId;

    @Schema(description = "用户id")
    private Long userId;

}
