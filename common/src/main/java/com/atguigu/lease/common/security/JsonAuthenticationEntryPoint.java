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

@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final ResultCodeEnum defaultResultCode;

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
