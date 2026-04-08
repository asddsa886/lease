package com.atguigu.lease.common.security;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Security 统一响应写出工具类。
 * <p>
 * 作用：
 * 1. 统一将认证/授权失败写成项目约定的 JSON 结构；
 * 2. 避免多个 Handler / EntryPoint 重复拼装响应体；
 * 3. 保证响应编码、Content-Type 与错误码格式一致。
 */
public final class SecurityResponseWriter {

    /**
     * 工具类不允许被实例化。
     */
    private SecurityResponseWriter() {
    }

    /**
     * 将安全相关异常响应写入 HttpServletResponse。
     *
     * @param response 当前响应对象，用于设置状态码与写出 JSON 内容
     * @param objectMapper Jackson 序列化工具，用于将 Result 对象转成 JSON
     * @param resultCodeEnum 业务错误码枚举，决定返回体中的 code 与 message
     * @param status HTTP 状态码，例如 401、403
     * @throws IOException 当响应输出流写出失败时抛出
     */
    public static void write(HttpServletResponse response, ObjectMapper objectMapper, ResultCodeEnum resultCodeEnum, int status)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(resultCodeEnum.getCode(), resultCodeEnum.getMessage())));
    }
}
