package com.atguigu.lease.common.security;

import com.atguigu.lease.common.result.ResultCodeEnum;
import org.springframework.security.core.AuthenticationException;

public class JwtAuthenticationException extends AuthenticationException {

    private final ResultCodeEnum resultCodeEnum;

    public JwtAuthenticationException(ResultCodeEnum resultCodeEnum) {
        super(resultCodeEnum.getMessage());
        this.resultCodeEnum = resultCodeEnum;
    }

    public ResultCodeEnum getResultCodeEnum() {
        return resultCodeEnum;
    }
}
