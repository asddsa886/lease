package com.atguigu.lease.web.admin.custom.config;

import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.security.JsonAccessDeniedHandler;
import com.atguigu.lease.common.security.JsonAuthenticationEntryPoint;
import com.atguigu.lease.common.security.JwtAuthenticationFilter;
import com.atguigu.lease.common.security.TokenBlacklistService;
import com.atguigu.lease.common.security.TokenClientType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Admin 端 Spring Security 配置类。
 * <p>
 * 作用：
 * 1. 定义后台管理端的安全过滤器链；
 * 2. 配置后台登录接口白名单与其余接口的认证要求；
 * 3. 接入 Admin 专用 JWT 认证过滤器；
 * 4. 配置后台端认证失败、权限不足时的统一 JSON 响应；
 * 5. 明确后台端采用 JWT 无状态认证，而非 Session。
 */
@Configuration
public class SecurityConfiguration {

    /**
     * 构建 Admin 端安全过滤器链。
     *
     * @param http Spring Security 的 HttpSecurity 配置入口
     * @param adminJwtAuthenticationFilter Admin 端 JWT 认证过滤器
     * @param adminAuthenticationEntryPoint Admin 端认证失败入口
     * @param objectMapper JSON 序列化工具，用于构造授权失败响应
     * @return 配置完成的 SecurityFilterChain
     * @throws Exception 当 Security 配置构建失败时抛出
     */
    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
                                                        JwtAuthenticationFilter adminJwtAuthenticationFilter,
                                                        AuthenticationEntryPoint adminAuthenticationEntryPoint,
                                                        ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/admin/login/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/webjars/**",
                                "/favicon.ico",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(adminAuthenticationEntryPoint)
                        .accessDeniedHandler(new JsonAccessDeniedHandler(objectMapper, ResultCodeEnum.ADMIN_ACCESS_FORBIDDEN))
                )
                .addFilterBefore(adminJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .securityContext(Customizer.withDefaults());
        return http.build();
    }

    /**
     * 声明 Admin 端认证失败入口。
     *
     * @param objectMapper JSON 序列化工具
     * @return Admin 端统一认证失败处理器
     */
    @Bean
    public AuthenticationEntryPoint adminAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new JsonAuthenticationEntryPoint(objectMapper, ResultCodeEnum.ADMIN_LOGIN_AUTH);
    }

    /**
     * 声明 Admin 端 JWT 认证过滤器。
     *
     * @param tokenBlacklistService token 黑名单服务，用于校验 token 是否已失效
     * @param adminAuthenticationEntryPoint Admin 端认证失败入口
     * @return Admin 端 JWT 认证过滤器实例
     */
    @Bean
    public JwtAuthenticationFilter adminJwtAuthenticationFilter(TokenBlacklistService tokenBlacklistService,
                                                                AuthenticationEntryPoint adminAuthenticationEntryPoint) {
        return new JwtAuthenticationFilter(TokenClientType.ADMIN, tokenBlacklistService, adminAuthenticationEntryPoint);
    }

    /**
     * 提供一个占位的 UserDetailsService。
     * <p>
     * 当前项目后台端同样采用 JWT 无状态认证，不使用 Spring Security 默认的用户名密码登录链路，
     * 因此这里的主要目的是关闭默认用户自动配置。
     *
     * @return 一个始终抛出异常的 UserDetailsService
     */
    @Bean
    public UserDetailsService adminUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("JWT-only authentication is enabled");
        };
    }
}
