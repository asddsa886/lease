package com.atguigu.lease.model.entity;

import com.atguigu.lease.model.enums.LeaseOrderStatus;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Schema(description = "签约订单表")
@TableName(value = "lease_order")
@Data
public class LeaseOrder extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单编号")
    @TableField(value = "order_no")
    private String orderNo;

    @Schema(description = "用户id")
    @TableField(value = "user_id")
    private Long userId;

    @Schema(description = "用户手机号")
    @TableField(value = "phone")
    private String phone;

    @Schema(description = "用户姓名")
    @TableField(value = "name")
    private String name;

    @Schema(description = "公寓id")
    @TableField(value = "apartment_id")
    private Long apartmentId;

    @Schema(description = "房间id")
    @TableField(value = "room_id")
    private Long roomId;

    @Schema(description = "租约开始日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField(value = "lease_start_date")
    private Date leaseStartDate;

    @Schema(description = "租约结束日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @TableField(value = "lease_end_date")
    private Date leaseEndDate;

    @Schema(description = "租期id")
    @TableField(value = "lease_term_id")
    private Long leaseTermId;

    @Schema(description = "租金（元/月）")
    @TableField(value = "rent")
    private BigDecimal rent;

    @Schema(description = "押金（元）")
    @TableField(value = "deposit")
    private BigDecimal deposit;

    @Schema(description = "支付方式id")
    @TableField(value = "payment_type_id")
    private Long paymentTypeId;

    @Schema(description = "订单状态")
    @TableField(value = "status")
    private LeaseOrderStatus status;

    @Schema(description = "备注信息")
    @TableField(value = "additional_info")
    private String additionalInfo;

    @Schema(description = "超时时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(value = "expire_time")
    private Date expireTime;

    @Schema(description = "关联租约id")
    @TableField(value = "agreement_id")
    private Long agreementId;
}
