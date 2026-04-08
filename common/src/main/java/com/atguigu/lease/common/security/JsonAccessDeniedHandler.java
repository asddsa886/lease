package com.atguigu.lease.common.security;

import com.atguigu.lease.common.result.ResultCodeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 自定义授权失败处理器。
 * <p>
 * 作用：
 * 1. 处理“已登录但权限不足”的场景；
 * 2. 替换 Spring Security 默认的 HTML 403 页面；
 * 3. 统一返回项目约定的 JSON 错误结构。
 */
@RequiredArgsConstructor
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    /**
     * Jackson 序列化工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 授权失败时返回的业务错误码。
     */
    private final ResultCodeEnum resultCodeEnum;

    /**
     * 处理授权失败请求。
     *
     * @param request 当前 HTTP 请求对象
     * @param response 当前 HTTP 响应对象
     * @param accessDeniedException 权限不足异常
     * @throws IOException 当写响应失败时抛出
     * @throws ServletException Servlet 规范保留异常
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        SecurityResponseWriter.write(response, objectMapper, resultCodeEnum, HttpServletResponse.SC_FORBIDDEN);
    }
}
