package com.atguigu.lease.web.admin.custom.config;

import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.security.JwtAuthenticationFilter;
import com.atguigu.lease.common.security.JwtSecurityConfigurationSupport;
import com.atguigu.lease.common.security.JsonAuthenticationEntryPoint;
import com.atguigu.lease.common.security.TokenBlacklistService;
import com.atguigu.lease.common.security.TokenClientType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {

    private static final String[] ADMIN_WHITELIST = {
            "/admin/login/**",
            "/doc.html",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/webjars/**",
            "/favicon.ico",
            "/error"
    };

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity http,
                                                        JwtAuthenticationFilter adminJwtAuthenticationFilter,
                                                        AuthenticationEntryPoint adminAuthenticationEntryPoint,
                                                        ObjectMapper objectMapper,
                                                        JwtSecurityConfigurationSupport securitySupport) throws Exception {
        return securitySupport.buildSecurityFilterChain(
                http,
                adminJwtAuthenticationFilter,
                adminAuthenticationEntryPoint,
                objectMapper,
                ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN,
                ADMIN_WHITELIST
        );
    }

    @Bean
    public AuthenticationEntryPoint adminAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper, ResultCodeEnum.ADMIN_LOGIN_AUTH);
    }

    @Bean
    public JwtAuthenticationFilter adminJwtAuthenticationFilter(TokenBlacklistService tokenBlacklistService,
                                                                AuthenticationEntryPoint adminAuthenticationEntryPoint) {
        return new JwtAuthenticationFilter(TokenClientType.ADMIN, tokenBlacklistService, adminAuthenticationEntryPoint);
    }

    @Bean
    public UserDetailsService adminUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("JWT-only authentication is enabled");
        };
    }
}
