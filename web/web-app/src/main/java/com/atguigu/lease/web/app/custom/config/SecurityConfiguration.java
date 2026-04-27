package com.atguigu.lease.web.app.custom.config;

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

    private static final String[] APP_WHITELIST = {
            "/app/login/**",
            "/assistant.html",
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
    public SecurityFilterChain appSecurityFilterChain(org.springframework.security.config.annotation.web.builders.HttpSecurity http,
                                                      JwtAuthenticationFilter appJwtAuthenticationFilter,
                                                      AuthenticationEntryPoint appAuthenticationEntryPoint,
                                                      ObjectMapper objectMapper,
                                                      JwtSecurityConfigurationSupport securitySupport) throws Exception {
        return securitySupport.buildSecurityFilterChain(
                http,
                appJwtAuthenticationFilter,
                appAuthenticationEntryPoint,
                objectMapper,
                ResultCodeEnum.ILLEGAL_REQUEST,
                APP_WHITELIST
        );
    }

    @Bean
    public AuthenticationEntryPoint appAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper, ResultCodeEnum.APP_LOGIN_AUTH);
    }

    @Bean
    public JwtAuthenticationFilter appJwtAuthenticationFilter(TokenBlacklistService tokenBlacklistService,
                                                              AuthenticationEntryPoint appAuthenticationEntryPoint) {
        return new JwtAuthenticationFilter(TokenClientType.APP, tokenBlacklistService, appAuthenticationEntryPoint);
    }

    @Bean
    public UserDetailsService appUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("JWT-only authentication is enabled");
        };
    }
}
