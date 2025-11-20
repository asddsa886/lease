package com.atguigu.lease.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;


public enum ItemType implements BaseEnum {

    APARTMENT(1, "公寓"),

    ROOM(2, "房间");


    // @EnumValue是给MyBatis-Plus看的，用于数据库持久化。 用于指导枚举与数据库字段值之间的转换。
    @EnumValue
    // @JsonValue：由Jackson库提供，用于指导枚举在序列化为JSON时的输出值 输出值为int而不是ROOM或者APARTMENT实例
    @JsonValue
    private Integer code;
    private String name;

    @Override
    public Integer getCode() {
        return this.code;
    }


    @Override
    public String getName() {
        return name;
    }

    ItemType(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

}
