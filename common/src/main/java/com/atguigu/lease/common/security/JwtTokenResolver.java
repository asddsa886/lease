package com.atguigu.lease.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * JWT 提取工具类。
 * <p>
 * 作用：
 * 1. 统一从 HTTP 请求头中提取 token；
 * 2. 兼容项目当前使用的 {@code access-token} 请求头；
 * 3. 同时兼容标准的 {@code Authorization: Bearer xxx} 形式；
 * 4. 避免过滤器、控制器等位置重复编写取 token 逻辑。
 */
public final class JwtTokenResolver {

    /**
     * 项目当前前后端约定使用的自定义 token 请求头。
     */
    public static final String ACCESS_TOKEN_HEADER = "access-token";

    /**
     * 标准 Bearer Token 请求头。
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * 标准 Bearer Token 前缀。
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 工具类不允许被实例化。
     */
    private JwtTokenResolver() {
    }

    /**
     * 从请求中解析 token。
     * <p>
     * 解析顺序：
     * 1. 先读取项目自定义请求头 {@code access-token}；
     * 2. 若不存在，再读取标准 {@code Authorization} 请求头；
     * 3. 若 Authorization 以 {@code Bearer } 开头，则截取其后的 token；
     * 4. 若都不存在，则返回 {@code null}。
     *
     * @param request 当前 HTTP 请求对象，用于读取请求头
     * @return 解析出的 token；若请求中未携带 token，则返回 {@code null}
     */
    public static String resolve(HttpServletRequest request) {
        String accessToken = request.getHeader(ACCESS_TOKEN_HEADER);
        if (StringUtils.hasText(accessToken)) {
            return accessToken.trim();
        }

        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(authorization) && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
