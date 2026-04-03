package com.atguigu.lease.common.security;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SecurityResponseWriter {

    private SecurityResponseWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper, ResultCodeEnum resultCodeEnum, int status)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(resultCodeEnum.getCode(), resultCodeEnum.getMessage())));
    }
}
