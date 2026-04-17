package com.atguigu.lease.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LeaseOrderStatus implements BaseEnum {

    PENDING_PAYMENT(1, "待支付"),
    PAID(2, "已支付"),
    CONFIRMED(3, "已确认"),
    CANCELED(4, "已取消"),
    TIMEOUT_CANCELED(5, "超时取消");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String name;

    LeaseOrderStatus(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }
}
