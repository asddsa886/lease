package com.atguigu.lease.common.security;

import com.atguigu.lease.common.result.ResultCodeEnum;
import org.springframework.security.core.AuthenticationException;

/**
 * JWT 认证异常。
 * <p>
 * 作用：
 * 1. 将项目里的 {@link ResultCodeEnum} 包装为 Spring Security 可识别的认证异常；
 * 2. 让认证失败时既能走 Spring Security 的异常处理流程，
 *    又能保留项目原有的错误码与错误信息语义；
 * 3. 供 {@link JsonAuthenticationEntryPoint} 统一转换成 JSON 响应。
 */
public class JwtAuthenticationException extends AuthenticationException {

    /**
     * 对应的业务错误码枚举。
     */
    private final ResultCodeEnum resultCodeEnum;

    /**
     * 构造 JWT 认证异常。
     *
     * @param resultCodeEnum 认证失败对应的业务错误码，例如 token 过期、token 非法等
     */
    public JwtAuthenticationException(ResultCodeEnum resultCodeEnum) {
        super(resultCodeEnum.getMessage());
        this.resultCodeEnum = resultCodeEnum;
    }

    /**
     * 获取异常对应的业务错误码。
     *
     * @return 当前异常绑定的业务错误码枚举
     */
    public ResultCodeEnum getResultCodeEnum() {
        return resultCodeEnum;
    }
}
