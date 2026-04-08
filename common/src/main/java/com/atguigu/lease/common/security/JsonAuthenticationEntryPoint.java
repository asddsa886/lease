package com.atguigu.lease.common.security;

import com.atguigu.lease.common.result.ResultCodeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 自定义认证失败入口。
 * <p>
 * 作用：
 * 1. 处理“未登录 / token 无效 / token 过期”等认证失败场景；
 * 2. 替换 Spring Security 默认的 HTML 错误页输出；
 * 3. 将认证失败统一转换成项目标准 JSON 响应。
 */
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /**
     * Jackson 序列化工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 默认的业务错误码。
     * <p>
     * 当异常中没有更具体的 JWT 错误码时，使用该默认值返回。
     */
    private final ResultCodeEnum defaultResultCode;

    /**
     * 认证失败时的统一处理入口。
     * <p>
     * 处理逻辑：
     * 1. 先尝试从异常本身或其 cause 中提取 {@link JwtAuthenticationException}；
     * 2. 若能提取到，则使用其中携带的业务错误码；
     * 3. 否则退回到构造时传入的默认错误码；
     * 4. 最终写出 401 JSON 响应。
     *
     * @param request 当前 HTTP 请求对象
     * @param response 当前 HTTP 响应对象
     * @param authException Spring Security 抛出的认证异常
     * @throws IOException 当写响应失败时抛出
     * @throws ServletException Servlet 规范保留异常
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        ResultCodeEnum resultCodeEnum = defaultResultCode;
        if (authException instanceof JwtAuthenticationException jwtAuthenticationException) {
            resultCodeEnum = jwtAuthenticationException.getResultCodeEnum();
        } else if (authException.getCause() instanceof JwtAuthenticationException jwtAuthenticationException) {
            resultCodeEnum = jwtAuthenticationException.getResultCodeEnum();
        }
        SecurityResponseWriter.write(response, objectMapper, resultCodeEnum, HttpServletResponse.SC_UNAUTHORIZED);
    }
}
