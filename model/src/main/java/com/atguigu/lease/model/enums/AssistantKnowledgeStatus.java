package com.atguigu.lease.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AssistantKnowledgeStatus implements BaseEnum {

    UPLOADED(1, "已上传"),
    INDEXING(2, "索引中"),
    INDEXED(3, "已索引"),
    FAILED(4, "索引失败");

    @EnumValue
    @JsonValue
    private final Integer code;

    private final String name;

    AssistantKnowledgeStatus(Integer code, String name) {
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
