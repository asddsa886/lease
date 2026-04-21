package com.atguigu.lease.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AssistantKnowledgeScope implements BaseEnum {

    GLOBAL(1, "平台通用"),
    APARTMENT(2, "公寓知识");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String name;

    AssistantKnowledgeScope(Integer code, String name) {
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
