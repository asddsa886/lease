package com.atguigu.lease.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

public final class JwtTokenResolver {

    public static final String ACCESS_TOKEN_HEADER = "access-token";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private JwtTokenResolver() {
    }

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
