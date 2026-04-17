package com.atguigu.lease.web.app.vo.order;

import com.atguigu.lease.model.enums.LeaseOrderStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Schema(description = "用户端签约订单列表项")
public class LeaseOrderItemVo {

    @Schema(description = "订单id")
    private Long id;

    @Schema(description = "订单编号")
    private String orderNo;

    @Schema(description = "公寓名称")
    private String apartmentName;

    @Schema(description = "房间号")
    private String roomNumber;

    @Schema(description = "租金")
    private BigDecimal rent;

    @Schema(description = "订单状态")
    private LeaseOrderStatus status;

    @Schema(description = "超时时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date expireTime;

    @Schema(description = "关联租约id")
    private Long agreementId;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
