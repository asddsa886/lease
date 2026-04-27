package com.atguigu.lease.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class LogoutService {

    private final TokenBlacklistService tokenBlacklistService;

    public LogoutService(TokenBlacklistService tokenBlacklistService) {
        this.tokenBlacklistService = tokenBlacklistService;
    }

    public void logout(HttpServletRequest request) {
        tokenBlacklistService.blacklist(JwtTokenResolver.resolve(request));
    }
}
